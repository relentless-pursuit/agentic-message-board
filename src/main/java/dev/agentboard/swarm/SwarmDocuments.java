package dev.agentboard.swarm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Deterministic Word export of user-approved text; no model calls or silent rewrites. */
public final class SwarmDocuments {
    private SwarmDocuments(){}
    public static byte[] docx(String text) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)){
            put(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            put(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            StringBuilder body=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            for(String line:text.split("\\R",-1))body.append("<w:p><w:pPr><w:spacing w:after=\"100\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/><w:sz w:val=\"21\"/></w:rPr><w:t xml:space=\"preserve\">").append(escape(line)).append("</w:t></w:r></w:p>");
            body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"864\" w:right=\"864\" w:bottom=\"864\" w:left=\"864\"/></w:sectPr></w:body></w:document>");put(zip,"word/document.xml",body.toString());
        }return bytes.toByteArray();
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static void put(ZipOutputStream zip,String name,String text)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
