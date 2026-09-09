package com.kratisai.controlplane;

import java.lang.annotation.*;

/**
 * Annotation to mark that a test method or class requires real Spring AI
 * model connections rather than returning a mocked Chat/Embedding models.
 * <p/>
 * Relies on messy ResetChatModelTestExecutionListener
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UseRealLlmClient {}
