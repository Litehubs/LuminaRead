package com.luminaread.parser;

import com.luminaread.model.BookContent;
import java.io.File;

public interface BookParser {
    BookContent parse(File file) throws Exception;
}
