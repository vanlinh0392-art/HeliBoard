// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import java.text.Normalizer

/**
 * Validates whether a composing word can be a valid Vietnamese syllable.
 * Used by VietnameseTelexCombiner to skip Telex transformations on non-Vietnamese words.
 *
 * Vietnamese syllable structure: (C1)(w) V (C2)
 * - C1: initial consonant (optional)
 * - w: glide/semivowel (optional)  
 * - V: vowel nucleus (required)
 * - C2: final consonant (optional)
 */
object VietnameseSyllableValidator {

    /**
     * Check if the current composing word could still become a valid Vietnamese syllable.
     * This is a "prefix check" - returns true if the word so far doesn't violate Vietnamese rules.
     * We are lenient: if the word is still being typed, we allow partial matches.
     */
    fun couldBeVietnamese(word: String): Boolean {
        if (word.isEmpty()) return true
        
        val normalized = stripDiacritics(word).lowercase()
        if (normalized.isEmpty()) return true
        
        // Rule 1: Check for invalid consonant clusters at the beginning
        if (hasInvalidInitialCluster(normalized)) return false
        
        // Rule 2: Check for too many consecutive consonants (>3 is never Vietnamese)
        if (hasTooManyConsecutiveConsonants(normalized)) return false
        
        // Rule 3: Native Vietnamese syllables have exactly ONE contiguous block of vowels
        if (hasMultipleVowelBlocks(normalized)) return false
        
        // Rule 4: Check for invalid character sequences anywhere in the word
        if (hasInvalidSequence(normalized)) return false
        
        // Rule 5: Check for invalid final consonant patterns
        if (hasInvalidFinalPattern(normalized)) return false
        
        return true
    }
    
    /**
     * Checks if the word has more than one contiguous block of vowels.
     * In Vietnamese, all vowels in a syllable group together (e.g. "nguyên" -> "uyê", "oanh" -> "oa").
     * A pattern like Vowel-Consonant-Vowel (e.g., "video", "between", "facebook") is impossible 
     * in a single native Vietnamese syllable and indicates a foreign word or missing space.
     */
    private fun hasMultipleVowelBlocks(word: String): Boolean {
        var foundVowelBlock = false
        var inVowelBlock = false
        
        for (c in word) {
            val isVowel = c in VOWELS_BASIC
            if (isVowel) {
                if (!inVowelBlock) {
                    if (foundVowelBlock) {
                        // Found a second distinct vowel block separated by consonants
                        return true
                    }
                    inVowelBlock = true
                    foundVowelBlock = true
                }
            } else {
                inVowelBlock = false
            }
        }
        return false
    }
    
    /**
     * Strip all Vietnamese diacritics/tone marks and convert special chars to base.
     * ă→a, â→a, ê→e, ô→o, ơ→o, ư→u, đ→d
     */
    private fun stripDiacritics(word: String): String {
        val nfd = Normalizer.normalize(word, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in nfd) {
            when {
                c == 'đ' || c == 'Đ' -> sb.append('d')
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {} // skip combining marks
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
    
    /**
     * Detect consonant clusters at the start of the word that don't exist in Vietnamese.
     */
    private fun hasInvalidInitialCluster(word: String): Boolean {
        // Get the initial consonant cluster (all consonants before the first vowel)
        val cluster = StringBuilder()
        for (c in word) {
            if (c in VOWELS_BASIC) break
            cluster.append(c)
        }
        
        val clusterStr = cluster.toString()
        if (clusterStr.length == 1) {
            // f, j, w, z are not valid initial consonants in Vietnamese.
            // Note: when 'w' is typed and meant to be 'ư', it transforms to 'ư' (a vowel),
            // making the cluster empty, so it bypasses this rule correctly!
            return clusterStr[0] in setOf('f', 'j', 'w', 'z')
        }
        
        if (clusterStr.length <= 1) return false // empty or other single consonant is OK
        
        // Check against valid Vietnamese initial consonants
        // Valid multi-char initials: ch, gh, gi, kh, ng, ngh, nh, ph, qu, th, tr
        if (clusterStr.length == 2) {
            return clusterStr !in VALID_DOUBLE_INITIALS
        }
        if (clusterStr.length == 3) {
            return clusterStr !in VALID_TRIPLE_INITIALS
        }
        
        // 4+ consonants at start is never Vietnamese
        return true
    }
    
    /**
     * Vietnamese never has more than 3 consecutive consonants (and only "ngh" qualifies for 3).
     * In practice, if we see 3+ consecutive consonants that aren't "ngh", it's not Vietnamese.
     */
    private fun hasTooManyConsecutiveConsonants(word: String): Boolean {
        var consecutiveConsonants = 0
        var consonantRun = StringBuilder()
        
        for (c in word) {
            if (c in VOWELS_BASIC) {
                consecutiveConsonants = 0
                consonantRun.clear()
            } else {
                consecutiveConsonants++
                consonantRun.append(c)
                
                if (consecutiveConsonants >= 3) {
                    val run = consonantRun.toString()
                    // "ngh" is the only valid 3-consonant cluster in Vietnamese (at word start)
                    if (run.length == 3 && run == "ngh") continue
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * Check for character sequences that never appear in Vietnamese.
     */
    private fun hasInvalidSequence(word: String): Boolean {
        for (seq in INVALID_SEQUENCES) {
            if (word.contains(seq)) return true
        }
        return false
    }
    
    /**
     * Check if the word ends with a consonant pattern that's invalid in Vietnamese.
     * Vietnamese only allows these final consonants: c, ch, m, n, ng, nh, p, t
     * 
     * We check this when the word has at least one vowel followed by consonants,
     * meaning the "final consonant" part is being formed.
     */
    private fun hasInvalidFinalPattern(word: String): Boolean {
        if (word.length < 3) return false
        
        // Find the last vowel position
        var lastVowelIndex = -1
        for (i in word.indices.reversed()) {
            if (word[i] in VOWELS_BASIC) {
                lastVowelIndex = i
                break
            }
        }
        
        // If no vowel found or vowel is at end, no final consonant to check
        if (lastVowelIndex < 0 || lastVowelIndex >= word.length - 1) return false
        
        // Get the final consonant cluster (everything after the last vowel)
        val finalCluster = word.substring(lastVowelIndex + 1)
        
        // Single final consonant
        if (finalCluster.length == 1) {
            return finalCluster[0] !in VALID_SINGLE_FINALS
        }
        
        // 2-char final consonant cluster
        if (finalCluster.length == 2) {
            return finalCluster !in VALID_DOUBLE_FINALS
        }
        
        // 3+ consonants after a vowel is never valid in Vietnamese
        return true
    }
    
    // Basic Latin vowels (after stripping diacritics: ă→a, â→a, ê→e, ô→o, ơ→o, ư→u)
    private val VOWELS_BASIC = setOf('a', 'e', 'i', 'o', 'u', 'y')
    
    // Valid 2-character initial consonant clusters in Vietnamese
    private val VALID_DOUBLE_INITIALS = setOf(
        "ch", "gh", "gi", "kh", "ng", "nh", "ph", "qu", "th", "tr"
    )
    
    // Valid 3-character initial consonant clusters in Vietnamese
    private val VALID_TRIPLE_INITIALS = setOf(
        "ngh"
    )
    
    // Valid single final consonants in Vietnamese
    private val VALID_SINGLE_FINALS = setOf(
        'c', 'm', 'n', 'p', 't'
        // Note: b, d, f, g, j, k, l, q, r, s, v, w, x, z are INVALID as finals
    )
    
    // Valid 2-character final consonant clusters in Vietnamese
    private val VALID_DOUBLE_FINALS = setOf(
        "ch", "ng", "nh"
        // Note: ck, ft, ld, lf, lk, lt, mb, mp, nd, nk, nt, pt, rd, rk, rm, rn etc. are INVALID
    )
    
    // Character sequences that NEVER appear in any Vietnamese word
    // These are common in English/European languages but structurally impossible in Vietnamese
    private val INVALID_SEQUENCES = setOf(
        // === Consonant clusters not in Vietnamese (2-char initial/medial) ===
        "bl", "br", "cl", "cr", "dr", "fl", "fr", "gl", "gr",
        "pl", "pr", "sc", "sk", "sl", "sm", "sn", "sp", "sq",
        "sr", "st", "sv", "sw", "tw", "wr", "wh", "sh",
        
        // === Consonant clusters not in Vietnamese (3+ char) ===
        "ght", "tch", "sch", "chr", "scr", "spl", "spr", "str",
        "ght", "ngl", "ngr", "ngs",
        
        // === Final consonant combos never in Vietnamese ===
        "ck", "ft", "ld", "lf", "lk", "lt", "lb", "lg", "lp", "ls",
        "mb", "mp", "nd", "nk", "nt", "ns", "nz",
        "pt", "ps",
        "rb", "rc", "rd", "rf", "rg", "rk", "rl", "rm", "rn", "rp", "rs", "rt", "rv", "rz",
        "sk", "lm",
        "wb", "wl", "wn", "ws",
        "xt", "xp",
        
        // === Vowel pairs never in Vietnamese (stripped of diacritics) ===
        // Note: "io" and "ou" are intentionally allowed here because they are
        // produced by valid Vietnamese syllables after stripping diacritics,
        // for example "giơ/giỏi" -> "gio/gioi" and "hươu/rượu" -> "huou/ruou".
        "ae", "ea", "ee", "ei", "ey", 
        "ii", "iy", 
        "oy", 
        "yi", "yo", "yu", "yy",
        
        // === Double consonants (never in Vietnamese) ===
        "bb", "cc", "dd", "ff", "gg", "hh", "jj", "kk", "ll",
        "mm", "nn", "pp", "qq", "rr", "ss", "tt", "vv", "ww",
        "xx", "zz"
        
        // Note: single chars f, j, w, z are NOT checked here because they serve as 
        // Telex modifier keys. The structural rules above catch non-Vietnamese patterns.
    )
}
