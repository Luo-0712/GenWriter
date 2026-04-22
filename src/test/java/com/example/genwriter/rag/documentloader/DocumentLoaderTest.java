package com.example.genwriter.rag.documentloader;

import com.example.genwriter.exception.DocumentLoadException;
import com.example.genwriter.model.entity.DocumentMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentLoaderTest {

    private DocumentLoader documentLoader;

    @BeforeEach
    void setUp() {
        documentLoader = new TikaDocumentLoader();
    }

    private String getTestDocumentPath() throws IOException {
        ClassPathResource resource = new ClassPathResource("test-document.md");
        return resource.getFile().getAbsolutePath();
    }

    @Test
    void testLoadDocument() throws DocumentLoadException, IOException {
        String filePath = getTestDocumentPath();
        String content = documentLoader.loadDocument(filePath);

        assertNotNull(content, "文档内容不应为null");
        assertTrue(content.contains("高斯消去法 (Gaussian Elimination) 教程"), "文档应包含标题");
        assertTrue(content.contains("基本原理"), "文档应包含基本原理部分");
        assertTrue(content.contains("朴素高斯消去法"), "文档应包含朴素高斯消去法部分");
        assertTrue(content.contains("列主元高斯消去法"), "文档应包含列主元高斯消去法部分");
        assertTrue(content.contains("完整示例"), "文档应包含完整示例");

        System.out.println("文档加载成功，内容长度: " + content.length() + " 字符");
        System.out.println("文档开头内容: " + content.substring(0, Math.min(200, content.length())));
    }

    @Test
    void testGetMetadata() throws DocumentLoadException, IOException {
        String filePath = getTestDocumentPath();
        DocumentMetadata metadata = documentLoader.getMetadata(filePath);

        assertNotNull(metadata, "元数据不应为null");
        assertTrue(metadata.getFileSize() > 0, "文件大小应大于0");

        System.out.println("文档元数据: " + metadata);
    }

    @Test
    void testSupportedFormats() {
        List<String> formats = documentLoader.supportedFormats();
        assertNotNull(formats, "支持的格式列表不应为null");
        assertTrue(formats.contains("all"), "应支持所有格式");
        System.out.println("支持的格式: " + formats);
    }
}
