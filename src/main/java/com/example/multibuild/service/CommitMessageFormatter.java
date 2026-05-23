package com.example.multibuild.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommitMessageFormatter {

    @Value("${commit.message.prefix:}")
    private String prefix;

    public String format(String message) {
        return prefix.isBlank() ? message : prefix + " " + message;
    }
}
