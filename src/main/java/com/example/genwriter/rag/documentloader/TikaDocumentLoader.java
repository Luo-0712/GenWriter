package com.example.genwriter.rag.documentloader;

import com.example.genwriter.exception.DocumentLoadException;
import com.example.genwriter.model.entity.DocumentMetadata;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Component
public class TikaDocumentLoader implements DocumentLoader {

    private final Tika tika = new Tika();

    @Override
    public String loadDocument(String filePath) throws DocumentLoadException {
        try {
            return tika.parseToString(new File(filePath));
        } catch (Exception e) {
            throw new DocumentLoadException("Failed to load document: " + filePath, e);
        }
    }

    @Override
    public List<String> supportedFormats() {
        return Arrays.asList("all");
    }

    @Override
    public DocumentMetadata getMetadata(String filePath) throws DocumentLoadException {
        try {
            File file = new File(filePath);
            Metadata metadata = new Metadata();
            
            try (FileInputStream fis = new FileInputStream(file)) {
                tika.parse(fis, metadata);
            }

            return DocumentMetadata.builder()
                .title(metadata.get(TikaCoreProperties.TITLE.getName()))
                .author(metadata.get(TikaCoreProperties.CREATOR.getName()))
                .fileSize(file.length())
                .contentType(metadata.get(Metadata.CONTENT_TYPE))
                .createdTime(extractCreationTime(metadata))
                .modifiedTime(extractModifiedTime(file, metadata))
                .build();

        } catch (Exception e) {
            throw new DocumentLoadException("Failed to extract metadata: " + filePath, e);
        }
    }

    private LocalDateTime extractCreationTime(Metadata metadata) {
        String[] timeFields = {
            TikaCoreProperties.CREATED.getName(),
            "Creation-Date",
            "created",
            "date"
        };
        for (String field : timeFields) {
            String value = metadata.get(field);
            if (value != null && !value.isEmpty()) {
                try {
                    return parseDateTime(value);
                } catch (Exception e) {
                    continue;
                }
            }
        }
        return null;
    }

    private LocalDateTime extractModifiedTime(File file, Metadata metadata) {
        String[] timeFields = {
            TikaCoreProperties.MODIFIED.getName(),
            "Last-Modified",
            "modified",
            "date"
        };
        for (String field : timeFields) {
            String value = metadata.get(field);
            if (value != null && !value.isEmpty()) {
                try {
                    return parseDateTime(value);
                } catch (Exception e) {
                    continue;
                }
            }
        }
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file.toPath()).toInstant(),
                ZoneId.systemDefault()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            Instant instant = Instant.from(formatter.parse(value));
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (Exception e) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
                return LocalDateTime.parse(value, formatter);
            } catch (Exception e2) {
                throw new RuntimeException("Failed to parse date: " + value, e2);
            }
        }
    }
}