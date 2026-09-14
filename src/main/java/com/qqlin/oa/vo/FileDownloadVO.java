package com.qqlin.oa.vo;

/**
 * 下载时要把三样东西一起交给 Controller：文件名、类型、内容。
 *
 * 用 record 是因为它就是个「数据袋子」，不需要可变性、不需要 setter。
 * Java 21 原生支持。
 */
public record FileDownloadVO(String fileName, String contentType, byte[] content) {
}
