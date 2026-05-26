package com.example.multibuild.event;

import com.example.multibuild.web.dto.BuildEventDto;

public record BuildProgressEvent(String sessionId, BuildEventDto payload) {}
