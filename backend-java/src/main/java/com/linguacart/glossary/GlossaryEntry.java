package com.linguacart.glossary;

public record GlossaryEntry(
        String english,
        String shona,
        String domain,
        String reviewer,
        String status
) {}
