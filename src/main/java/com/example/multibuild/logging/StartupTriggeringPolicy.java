package com.example.multibuild.logging;

import ch.qos.logback.core.rolling.TriggeringPolicyBase;

import java.io.File;

public class StartupTriggeringPolicy<E> extends TriggeringPolicyBase<E> {

    private boolean rolled = false;

    @Override
    public boolean isTriggeringEvent(File activeFile, E event) {
        if (!rolled) {
            rolled = true;
            return activeFile.length() > 0;
        }
        return false;
    }
}
