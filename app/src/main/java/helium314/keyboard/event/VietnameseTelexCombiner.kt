// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import java.text.Normalizer
import java.util.ArrayList

/**
 * Vietnamese Telex input method combiner.
 * Transforms key sequences into Vietnamese characters according to Telex rules:
 * - aa → â, ee → ê, oo → ô
 * - aw → ă, ow → ơ, uw → ư
 * - dd → đ
 * - s, f, r, x, j → tone marks (sắc, huyền, hỏi, ngã, nặng)
 * - z → remove tone
 */
class VietnameseTelexCombiner : Combiner {

    // Store as list of grapheme clusters for proper Unicode handling
    private val composingChars = mutableListOf<String>()
    
    // Track raw keystrokes to allow "restore keys on invalid word" (giới hạn phục hồi từ sai)
    private val rawKeys = mutableListOf<Char>()
    
    // Flag to track if we've already reverted this word.
    // Once reverted, we stop treating it as a Telex word to save processing.
    private var isRevertedToRaw = false

    // For escaped tone keys in longer Latin words, keep the live composing text minimal
    // (for example "tes"), but remember a possible raw commit form (for example "tess")
    // if the user ends the word immediately afterwards.
    private var pendingEscapedToneCommit: String? = null

    override fun processEvent(previousEvents: ArrayList<Event>?, event: Event): Event {
        // Wrap everything in try-catch to prevent any crashes
        try {
            if (event.keyCode == KeyCode.SHIFT) return event
            if (event.isCursorMove) return event
            
            val codePoint = event.codePoint
            
            // Handle whitespace - commit and pass through
            if (codePoint > 0 && Character.isWhitespace(codePoint)) {
                val text = textToCommitOnWordBoundary()
                reset()
                return if (text.isNotEmpty()) {
                    Event.createSoftwareTextEvent(text, KeyCode.MULTIPLE_CODE_POINTS, event)
                } else {
                    event
                }
            }
            
            // Handle functional keys (delete, etc.)
            if (event.isFunctionalKeyEvent) {
                if (event.keyCode == KeyCode.DELETE) {
                    pendingEscapedToneCommit = null
                    val size = composingChars.size
                    return when {
                        size == 0 -> {
                            // No composing text, pass delete through
                            event
                        }
                        size == 1 -> {
                            // Last character - just clear composing, do NOT forward delete
                            // (forwarding would delete the space before the word)
                            reset()
                            Event.createConsumedEvent(event)
                        }
                        else -> {
                            // Remove last character from composing
                            composingChars.removeAt(size - 1)
                            if (rawKeys.isNotEmpty()) {
                                rawKeys.removeAt(rawKeys.size - 1)
                            }
                            
                            // Re-evaluate validity after delete
                            if (isRevertedToRaw) {
                                // If it was reverted, check if it's valid again
                                val currentStr = composingChars.joinToString("")
                                if (VietnameseSyllableValidator.couldBeVietnamese(currentStr)) {
                                    // It became valid again! We could try to re-process the raw keys through Telex.
                                    // To keep it simple, we just allow typing to continue. 
                                    // A proper re-process would be better but complex. Let's just reset flag.
                                    isRevertedToRaw = false
                                }
                            }
                            
                            Event.createConsumedEvent(event)
                        }
                    }
                }
                // Commit text and pass through the functional key
                val text = textToCommitOnWordBoundary()
                reset()
                return if (text.isNotEmpty()) {
                    Event.createSoftwareTextEvent(text, KeyCode.MULTIPLE_CODE_POINTS, event)
                } else {
                    event
                }
            }
            
            // Not a letter - commit and pass through
            if (codePoint <= 0 || !Character.isLetter(codePoint)) {
                val text = textToCommitOnWordBoundary()
                reset()
                return if (text.isNotEmpty()) {
                    Event.createSoftwareTextEvent(text, KeyCode.MULTIPLE_CODE_POINTS, event)
                } else {
                    event
                }
            }
            
            val char = codePoint.toChar()
            val charLower = char.lowercaseChar()
            pendingEscapedToneCommit = null
            val rawWordWithCurrentKey = buildString {
                rawKeys.forEach { append(it) }
                append(char)
            }
            
            // Track raw char
            rawKeys.add(char)
            
            // If the word has already been deemed non-Vietnamese, just append raw keys
            if (isRevertedToRaw) {
                composingChars.add(char.toString())
                return Event.createConsumedEvent(event)
            }
            
            // --- Normal Telex processing ---
            
            // Let's try to transform the character using Telex rules
            val transformResult = if (charLower in TELEX_KEYS && composingChars.isNotEmpty()) {
                tryTransform(charLower)
            } else {
                0
            }
            
            // If the transformation was escaped (e.g. typing 'w' after 'ư' to get 'uw'),
            // we pop the previous key from rawKeys because the double-tap just cancelled the modifier!
            val isToneEscape = transformResult == 2 && charLower in TONE_KEYS
            if (transformResult == 2 && rawKeys.size > 1) {
                rawKeys.removeAt(rawKeys.size - 1)
            }
            
            if (transformResult == 0 || transformResult == 2) {
                // Regular letter fallback
                if (charLower == 'w') {
                    val lastChar = composingChars.lastOrNull()
                    val isPrecededByVowel = lastChar != null && getBaseChar(lastChar).lowercaseChar() in BASE_VOWELS
                    
                    if (isPrecededByVowel) {
                        composingChars.add(char.toString())
                    } else {
                        composingChars.add(if (Character.isUpperCase(codePoint)) "Ư" else "ư")
                    }
                } else {
                    composingChars.add(char.toString())
                }
            }
            
            // --- Smart Detection Check ---
            val resultingWord = composingChars.joinToString("")
            
            val isInvalidVietnamese = !VietnameseSyllableValidator.couldBeVietnamese(resultingWord)
            if (isInvalidVietnamese && shouldKeepCurrentLiteralWord(charLower, rawWordWithCurrentKey, resultingWord)) {
                freezeCurrentWordAsRaw()
            } else if (isInvalidVietnamese && isToneEscape) {
                if (shouldRestoreEscapedToneWordImmediately(rawWordWithCurrentKey, resultingWord)) {
                    freezeCurrentWordAsRaw()
                    pendingEscapedToneCommit = rawWordWithCurrentKey
                } else if (shouldDeferInvalidRevert()) {
                    freezeCurrentWordAsRaw()
                } else {
                    revertCurrentWordToRaw(rawKeys)
                }
            } else if (isInvalidVietnamese && !shouldDeferInvalidRevert()) {
                revertCurrentWordToRaw(rawKeys)
            }
            
            return Event.createConsumedEvent(event)
            
        } catch (e: Exception) {
            // On any error, reset and pass event through to prevent crash
            reset()
            return event
        }
    }
    
    override fun reset() {
        composingChars.clear()
        rawKeys.clear()
        isRevertedToRaw = false
        pendingEscapedToneCommit = null
    }

    private fun shouldDeferInvalidRevert(): Boolean {
        // Keep Telex transformations for short abbreviations like "đc".
        // We only fall back to raw keys once the composed word grows past 3 letters.
        return composingChars.size in 2..3
    }

    private fun shouldRestoreEscapedToneWordImmediately(
        rawWordWithCurrentKey: String,
        resultingWord: String,
    ): Boolean {
        if (rawWordWithCurrentKey.length < 4) return false
        return !containsVietnameseSpecificLetter(resultingWord)
    }

    private fun shouldKeepCurrentLiteralWord(
        keyLower: Char,
        rawWordWithCurrentKey: String,
        resultingWord: String,
    ): Boolean {
        if (keyLower != 'w') return false
        if (rawWordWithCurrentKey.length != 2) return false
        return resultingWord.equals("w", ignoreCase = true)
    }

    private fun containsVietnameseSpecificLetter(word: String): Boolean {
        for (charStr in wordToChars(word)) {
            val base = removeToneFromChar(charStr).lowercase()
            if (base in VIETNAMESE_SPECIFIC_BASES) {
                return true
            }
        }
        return false
    }

    private fun wordToChars(word: String): List<String> {
        val chars = mutableListOf<String>()
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            chars.add(String(Character.toChars(codePoint)))
            index += Character.charCount(codePoint)
        }
        return chars
    }

    private fun freezeCurrentWordAsRaw() {
        isRevertedToRaw = true
        pendingEscapedToneCommit = null
        rawKeys.clear()
        val currentWord = composingChars.joinToString("")
        currentWord.forEach { rawKeys.add(it) }
    }

    private fun revertCurrentWordToRaw(rawSource: Iterable<Char>) {
        val rawChars = rawSource.toList()
        isRevertedToRaw = true
        pendingEscapedToneCommit = null
        composingChars.clear()
        rawKeys.clear()
        for (rawC in rawChars) {
            composingChars.add(rawC.toString())
            rawKeys.add(rawC)
        }
    }

    private fun textToCommitOnWordBoundary(): CharSequence {
        val pending = pendingEscapedToneCommit
        return pending ?: combiningStateFeedback
    }



    private fun tryTransform(keyLower: Char): Int {
        try {
            when (keyLower) {
                'a' -> return transformRoof('a', 'â')
                'e' -> return transformRoof('e', 'ê')
                'o' -> return transformRoof('o', 'ô')
                'd' -> return transformDStroke()
                'w' -> return transformW()
                's' -> return applyTone('\u0301')
                'f' -> return applyTone('\u0300')
                'r' -> return applyTone('\u0309')
                'x' -> return applyTone('\u0303')
                'j' -> return applyTone('\u0323')
                'z' -> return if (removeTone()) 1 else 0
            }
            return 0
        } catch (e: Exception) {
            return 0
        }
    }
    
    // aw → ă, ow → ơ, uw → ư
    // Supports retroactive update (e.g. duongw -> đương)
    private fun transformW(): Int {
        // Scan backwards to find the last transformable vowel/vowel-pair
        // Priority: uo -> ươ, then single u/o/a.
        
        for (i in composingChars.indices.reversed()) {
             val charStr = composingChars[i]
             val baseChar = getBaseChar(charStr)
             val baseLower = baseChar.lowercaseChar()
             
             // Check for 'uo' pair first (if i > 0)
             if (i > 0) {
                 val prevIndex = i - 1
                 val prevCharStr = composingChars[prevIndex]
                 val prevBase = getBaseChar(prevCharStr).lowercaseChar()
                 if (prevBase == 'u' && baseLower == 'o') {
                     // Found 'uo', transform to 'ươ'
                     transformPairToUO(prevIndex, i)
                     return 1
                 }
                 
                 // If we found 'ươ' already, maybe toggle back? 
                 // (User requests standard typing, usually w toggles ươ -> uo)
                 if (prevBase == 'ư' && baseLower == 'ơ') {
                     transformPairToUORevert(prevIndex, i)
                     // Revert: return 2 so 'w' is appended (e.g. do -> dơ -> dow)
                     return 2 
                 }
             }
             
             // Check single chars
             if (baseLower in setOf('a', 'o', 'u', 'ă', 'ơ', 'ư')) {
                 val res = transformSingleW(i)
                 if (res == 1) return 1 // Consumed
                 if (res == 2) return 2 // Escaped -> Append 'w' and pop rawKeys
                 // 0 -> continue scanning
             }
        }
        return 0
    }

    private fun transformPairToUO(prevIndex: Int, currIndex: Int) {
         val prevChar = composingChars[prevIndex]
         val currChar = composingChars[currIndex]
         
         val toneLast = getToneMark(currChar)
         val tonePrev = getToneMark(prevChar)
         val existingTone = toneLast ?: tonePrev
         
         val prevIsUpper = getBaseChar(prevChar).isUpperCase()
         val newPrev = if (prevIsUpper) 'Ư' else 'ư'
         composingChars[prevIndex] = newPrev.toString()
         
         val lastIsUpper = getBaseChar(currChar).isUpperCase()
         val newLast = if (lastIsUpper) 'Ơ' else 'ơ'
         
         val result = if (existingTone != null) {
             Normalizer.normalize(newLast.toString() + existingTone, Normalizer.Form.NFC)
         } else {
             newLast.toString()
         }
         composingChars[currIndex] = result
    }

    private fun transformPairToUORevert(prevIndex: Int, currIndex: Int) {
         val prevChar = composingChars[prevIndex]
         val currChar = composingChars[currIndex]
         
         val toneLast = getToneMark(currChar)
         val tonePrev = getToneMark(prevChar)
         val existingTone = toneLast ?: tonePrev
         
         val prevIsUpper = getBaseChar(prevChar).isUpperCase()
         val newPrev = if (prevIsUpper) 'U' else 'u'
         composingChars[prevIndex] = newPrev.toString()
         
         val lastIsUpper = getBaseChar(currChar).isUpperCase()
         val newLast = if (lastIsUpper) 'O' else 'o'
         
         val result = if (existingTone != null) {
             Normalizer.normalize(newLast.toString() + existingTone, Normalizer.Form.NFC)
         } else {
             newLast.toString()
         }
         composingChars[currIndex] = result
    }

    // Returns: 0 = Continue/Unchanged, 1 = Consumed (Transformed), 2 = Reverted (Append 'w')
    private fun transformSingleW(index: Int): Int {
        val charStr = composingChars[index]
        val charNoTone = removeToneFromChar(charStr)
        val lower = charNoTone.lowercase()
        val baseChar = getBaseChar(charStr)
        val isUpper = baseChar.isUpperCase()

        // Revert logic
        var original: Char? = null
        if (lower == "ă") original = 'a'
        else if (lower == "ơ") original = 'o'
        else if (lower == "ư") {
             // Special case: if this is the only char and it is ư, revert to w
             if (composingChars.size == 1 && index == 0) {
                 original = 'w'
             } else {
                 original = 'u' 
             }
        }
        
        if (original != null) {
            val replacement = if (isUpper) original.uppercaseChar() else original
            val tone = getToneMark(charStr)
            val result = if (tone != null) {
                Normalizer.normalize(replacement.toString() + tone, Normalizer.Form.NFC)
            } else {
                replacement.toString()
            }
            composingChars[index] = result
            // If original was 'w', we consumed the toggle (w <-> ư).
            // If original was a vowel (o, u, a), we reverted it AND want to append 'w' now.
            return if (original == 'w') 1 else 2
        }

        // Apply logic
        val replacementChar = when (lower) {
            "a" -> if (isUpper) 'Ă' else 'ă'
            "o" -> if (isUpper) 'Ơ' else 'ơ'
            "u" -> if (isUpper) 'Ư' else 'ư'
            else -> null
        }

        if (replacementChar != null) {
            val tone = getToneMark(charStr)
            val result = if (tone != null) {
                Normalizer.normalize(replacementChar.toString() + tone, Normalizer.Form.NFC)
            } else {
                replacementChar.toString()
            }
            composingChars[index] = result
            return 1 // Consumed w to transform
        }
        
        return 0 // Continue
    }

    
    // aa → â, ee → ê, oo → ô
    private fun transformRoof(base: Char, replacement: Char): Int {
        val lastChar = composingChars.lastOrNull() ?: return 0
        
        // Check if we should revert (toggle): â + a -> a + a (return 2)
        val lastCharNoTone = removeToneFromChar(lastChar)
        if (lastCharNoTone.lowercase() == replacement.toString()) {
            val tone = getToneMark(lastChar)
            // Determine case from the current char
            val isUpper = lastCharNoTone.first().isUpperCase()
            val originalBase = if (isUpper) base.uppercaseChar() else base
            
            val result = if (tone != null) {
                Normalizer.normalize(originalBase.toString() + tone, Normalizer.Form.NFC)
            } else {
                originalBase.toString()
            }
            composingChars[composingChars.lastIndex] = result
            return 2
        }

        val baseChar = getBaseChar(lastChar)
        
        if (baseChar.lowercaseChar() == base) {
            val isUpper = baseChar.isUpperCase()
            val newChar = if (isUpper) replacement.uppercaseChar() else replacement
            // Preserve any existing tone mark
            val tone = getToneMark(lastChar)
            val result = if (tone != null) {
                Normalizer.normalize(newChar.toString() + tone, Normalizer.Form.NFC)
            } else {
                newChar.toString()
            }
            composingChars[composingChars.lastIndex] = result
            return 1
        }
        return 0
    }
    
    // dd → đ
    private fun transformDStroke(): Int {
        val lastChar = composingChars.lastOrNull() ?: return 0
        
        // Toggle logic: đ + d -> d + d
        val lastCharNoTone = removeToneFromChar(lastChar)
        if (lastCharNoTone.lowercase() == "đ") {
            val tone = getToneMark(lastChar)
            val isUpper = lastCharNoTone.first().isUpperCase()
            // Revert 'đ' to 'd'
            val originalBase = if (isUpper) 'D' else 'd'
            
            val result = if (tone != null) {
                Normalizer.normalize(originalBase.toString() + tone, Normalizer.Form.NFC)
            } else {
                originalBase.toString()
            }
            composingChars[composingChars.lastIndex] = result
            return 2
        }

        val baseChar = getBaseChar(lastChar)
        
        if (baseChar.lowercaseChar() == 'd') {
            val replacement = if (baseChar.isUpperCase()) 'Đ' else 'đ'
            composingChars[composingChars.lastIndex] = replacement.toString()
            return 1
        }
        return 0
    }
    
    // Apply tone mark to the appropriate vowel
    private fun applyTone(toneMark: Char): Int {
        // Check if the same tone is already present on any character in the word
        var existingToneIndex = -1
        for (i in composingChars.indices) {
            val tone = getToneMark(composingChars[i])
            if (tone == toneMark) {
                existingToneIndex = i
                break
            }
        }

        // TOGGLE LOGIC: If same tone exists, remove it and return 2.
        // This allows the key (e.g. 's') to be processed as a regular character.
        // Example: "á" + "s" -> "as" (remove sắc, append s)
        if (existingToneIndex >= 0) {
            composingChars[existingToneIndex] = removeToneFromChar(composingChars[existingToneIndex])
            return 2
        }

        // First remove any existing tone from all chars (if specific tone didn't match)
        for (i in composingChars.indices) {
            composingChars[i] = removeToneFromChar(composingChars[i])
        }
        
        // Find the vowel to apply tone to
        val vowelIndex = findTonePosition()
        if (vowelIndex < 0) return 0
        
        val vowelChar = composingChars[vowelIndex]
        val toned = Normalizer.normalize(vowelChar + toneMark.toString(), Normalizer.Form.NFC)
        composingChars[vowelIndex] = toned
        return 1
    }
    
    // Remove tone from the word
    private fun removeTone(): Boolean {
        var changed = false
        for (i in composingChars.indices) {
            val original = composingChars[i]
            val noTone = removeToneFromChar(original)
            if (noTone != original) {
                composingChars[i] = noTone
                changed = true
            }
        }
        return changed
    }
    
    private fun removeToneFromChar(char: String): String {
        val nfd = Normalizer.normalize(char, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in nfd) {
            if (c !in TONE_MARKS) {
                sb.append(c)
            }
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }
    
    private fun getBaseChar(char: String): Char {
        val nfd = Normalizer.normalize(char, Normalizer.Form.NFD)
        return nfd.firstOrNull() ?: ' '
    }
    
    private fun getToneMark(char: String): Char? {
        val nfd = Normalizer.normalize(char, Normalizer.Form.NFD)
        return nfd.find { it in TONE_MARKS }
    }
    
    // Find the position to place tone mark according to Vietnamese rules
    // Standard Vietnamese rules:
    // 1. If word contains ơ or ư, tone goes on that vowel
    // 2. If word ends with consonant, tone on last vowel
    // 3. If word ends with vowel, for diphthong/triphthong:
    //    - oa, oe, uy: tone on second vowel
    //    - Other 2 vowels ending in vowel: tone on first vowel
    //    - 3 vowels: tone on middle vowel
    private fun findTonePosition(): Int {
        val vowelPositions = mutableListOf<Int>()
        
        for (i in composingChars.indices) {
            val charStr = composingChars[i]
            val baseChar = getBaseChar(charStr).lowercaseChar()
            if (baseChar in BASE_VOWELS) {
                // Handle special consonant-vowel clusters where the first vowel is actually part of consonant
                if (i > 0) {
                    val prevBase = getBaseChar(composingChars[i-1]).lowercaseChar()
                    // 'qu' -> 'u' is not a vowel for tone placement
                    if (prevBase == 'q' && baseChar == 'u') continue
                    
                    // 'gi' -> 'i' is not a vowel if followed by another vowel (e.g., 'già')
                    if (prevBase == 'g' && baseChar == 'i') {
                        // Check if there is a next vowel
                        var hasNextVowel = false
                        for (j in i + 1 until composingChars.size) {
                             if (getBaseChar(composingChars[j]).lowercaseChar() in BASE_VOWELS) {
                                 hasNextVowel = true
                                 break
                             }
                        }
                        if (hasNextVowel) continue
                    }
                }
                vowelPositions.add(i)
            }
        }
        
        if (vowelPositions.isEmpty()) return -1
        if (vowelPositions.size == 1) return vowelPositions[0]
        
        // Priority: ơ, ư, ê, ô, â, ă get the tone if present
        // FIX: Must check the char WITH roof (but without tone marks)
        
        // Check for ươ pair specifically first
        for (i in 0 until vowelPositions.size - 1) {
            val idx1 = vowelPositions[i]
            val idx2 = vowelPositions[i+1]
            // If we have adjacent vowels ư and ơ
            if (idx2 == idx1 + 1) {
                val c1 = removeToneFromChar(composingChars[idx1]).lowercase()
                val c2 = removeToneFromChar(composingChars[idx2]).lowercase()
                if (c1 == "ư" && c2 == "ơ") {
                    return idx2 // Return ơ
                }
            }
        }
        
        for (i in vowelPositions) {
            val charNoTone = removeToneFromChar(composingChars[i])
            val firstChar = charNoTone.firstOrNull()?.lowercaseChar()
            if (firstChar != null && firstChar in SPECIAL_VOWELS) {
                return i
            }
        }
        
        // Check if ends with consonant
        val lastBaseChar = getBaseChar(composingChars.last()).lowercaseChar()
        val endsWithConsonant = lastBaseChar !in BASE_VOWELS
        
        if (vowelPositions.size >= 3) {
            // 3+ vowels: middle vowel
            return vowelPositions[1]
        }
        
        // 2 vowels
        if (vowelPositions.size == 2) {
            val v1 = getBaseChar(composingChars[vowelPositions[0]]).lowercaseChar()
            val v2 = getBaseChar(composingChars[vowelPositions[1]]).lowercaseChar()
            val cluster = "$v1$v2"
            
            // Special patterns: oa, oe, uy -> second vowel (Old Style) or first (New Style)
            if (cluster in SPECIAL_DIPHTHONGS) {
                return if (USE_NEW_STYLE_TONE) vowelPositions[1] else vowelPositions[0]
            }
            
            // Ends with consonant -> last vowel (e.g. 'uan' -> 'a')
            if (endsWithConsonant) {
                return vowelPositions[1]
            }
            
            // Ends with vowel -> first vowel (e.g. 'ua' -> 'u', 'ia' -> 'i')
            return vowelPositions[0]
        }
        
        return vowelPositions[0]
    }

    override val combiningStateFeedback: CharSequence
        get() = composingChars.joinToString("")


    companion object {
        private val TELEX_KEYS = setOf('a', 'e', 'o', 'd', 'w', 's', 'f', 'r', 'x', 'j', 'z')
        private val TONE_KEYS = setOf('s', 'f', 'r', 'x', 'j')
        private val BASE_VOWELS = setOf('a', 'ă', 'â', 'e', 'ê', 'i', 'o', 'ô', 'ơ', 'u', 'ư', 'y')
        private val SPECIAL_VOWELS = setOf('ơ', 'ư', 'ê', 'ô', 'â', 'ă') // These get priority for tones
        private val SPECIAL_DIPHTHONGS = setOf("oa", "oe", "uy")
        private val VIETNAMESE_SPECIFIC_BASES = setOf("ă", "â", "ê", "ô", "ơ", "ư", "đ")
        private const val USE_NEW_STYLE_TONE = true // Feature flag: true = hòa, false = hoà
        private val TONE_MARKS = setOf(
            '\u0301', // acute (sắc)
            '\u0300', // grave (huyền)
            '\u0309', // hook above (hỏi)
            '\u0303', // tilde (ngã)
            '\u0323'  // dot below (nặng)
        )
    }
}
