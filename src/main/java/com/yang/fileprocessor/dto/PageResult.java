package com.yang.fileprocessor.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用分页响应 DTO
 *
 * @param <T> 列表元素类型
 * @author yangyunpu
 * @since 2026-07-15
 */
@Data
public class PageResult<T> {
    /** 当前页数据列表 */
    private List<T> list;
    /** 总记录数 */
    private long total;
    /** 当前页码 */
    private int page;
    /** 每页大小 */
    private int size;
    /** 总页数 */
    private int totalPages;
}
