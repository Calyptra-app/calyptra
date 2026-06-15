package com.calyptra.app.blocklist

class DomainMatcher(private val blocklist: Set<String>) {
    
    fun isBlocked(domain: String): Boolean {
        var current = domain.lowercase()
        // Check exact match first
        if (blocklist.contains(current)) return true
        
        // Check parent domains
        while (current.contains('.')) {
            if (blocklist.contains(current)) return true
            current = current.substringAfter('.')
        }
        return blocklist.contains(current)
    }
}