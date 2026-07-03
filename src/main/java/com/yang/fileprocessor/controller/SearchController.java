package com.yang.fileprocessor.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yang.fileprocessor.dto.Result;
import com.yang.fileprocessor.entity.FileDocument;
import com.yang.fileprocessor.entity.FileInfo;
import com.yang.fileprocessor.service.FileInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private FileInfoService fileInfoService;

    @GetMapping
    public Result search(@RequestParam("keyword") String keyword) {
        try {
            // 1. 从 ES 搜索匹配的 fileId
            SearchResponse<FileDocument> response = elasticsearchClient.search(s -> s
                            .index("file_index")
                            .query(q -> q
                                    .multiMatch(m -> m
                                            .fields("fileName", "content")
                                            .query(keyword)
                                    )
                            ),
                    FileDocument.class
            );

            // 2. 从 MySQL 查询完整的文件信息
            List<FileInfo> results = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                FileDocument doc = hit.source();
                if (doc != null && doc.getId() != null) {
                    FileInfo fileInfo = fileInfoService.getById(doc.getId());
                    if (fileInfo != null) {
                        results.add(fileInfo);
                    }
                }
            });

            return Result.success(results);

        } catch (IOException e) {
            return Result.error("搜索失败：" + e.getMessage());
        }
    }
}