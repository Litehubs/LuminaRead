package com.luminaread.service;

/**
 * Service interface for dictionary lookup and text translation.
 */
public interface IDictionaryService {

    /**
     * Look up word definition and/or translate selected text between supported languages.
     *
     * @param selectedText the text snippet or word selected by the user
     * @return formatted translation and/or dictionary definitions
     */
    String lookupAndTranslate(String selectedText);
}
