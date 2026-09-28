package com.ruoyi.web.service.mall;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;

/** Shared, versioned operational media and page-decoration configuration. */
@Service
public class MallPageDecorationService
{
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_IMAGE_EDGE = 12000;
    private static final List<String> EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");
    private static final List<String> MIME_TYPES = Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    public List<Map<String, Object>> publicModules()
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.id,m.module_key AS moduleKey,m.page_code AS pageCode,m.module_code AS moduleCode," +
                "m.english_title AS englishTitle,m.title,m.subtitle,m.description,m.image_url AS imageUrl," +
                "m.jump_type AS jumpType,m.jump_target AS jumpTarget,m.config_json AS configJson," +
                "m.sort_no AS sortNo,m.publish_time AS publishTime,m.version_no AS versionNo " +
                "FROM mall_page_module m WHERE m.status='0' AND (m.publish_time IS NULL OR m.publish_time<=NOW()) " +
                "ORDER BY m.page_code,m.sort_no,m.id");
        for (Map<String, Object> row : rows) attachConfig(row);
        return rows;
    }

    public List<Map<String, Object>> adminMaterials()
    {
        return jdbc.queryForList(
                "SELECT id,asset_no AS assetNo,name,url,mime_type AS mimeType,extension,width,height,size_bytes AS sizeBytes," +
                "purpose,page_code AS pageCode,module_code AS moduleCode,recommended_size AS recommendedSize,status," +
                "sort_no AS sortNo,version_no AS versionNo,create_time AS createTime,update_time AS updateTime " +
                "FROM mall_media_asset ORDER BY status,sort_no,id DESC");
    }

    public List<Map<String, Object>> adminModules()
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.id,m.module_key AS moduleKey,m.page_code AS pageCode,m.module_code AS moduleCode," +
                "m.english_title AS englishTitle,m.title,m.subtitle,m.description,m.image_asset_id AS imageAssetId," +
                "m.image_url AS imageUrl,m.jump_type AS jumpType,m.jump_target AS jumpTarget,m.config_json AS configJson," +
                "m.status,m.sort_no AS sortNo,m.publish_time AS publishTime,m.version_no AS versionNo," +
                "m.create_time AS createTime,m.update_time AS updateTime,a.name AS assetName " +
                "FROM mall_page_module m LEFT JOIN mall_media_asset a ON a.id=m.image_asset_id ORDER BY m.page_code,m.sort_no,m.id");
        for (Map<String, Object> row : rows) attachConfig(row);
        return rows;
    }

    public List<Map<String, Object>> history(Long moduleId)
    {
        return jdbc.queryForList(
                "SELECT id,module_id AS moduleId,version_no AS versionNo,operator_id AS operatorId,create_time AS createTime " +
                "FROM mall_page_module_history WHERE module_id=? ORDER BY version_no DESC,id DESC", moduleId);
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file, Map<String, String> meta) throws Exception
    {
        ImageInfo info = validateImage(file);
        String url = FileUploadUtils.upload(RuoYiConfig.getProfile() + "/mall-materials", file,
                MimeTypeUtils.IMAGE_EXTENSION, true);
        String assetNo = "MEDIA-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO mall_media_asset(asset_no,name,url,mime_type,extension,width,height,size_bytes,purpose," +
                "page_code,module_code,recommended_size,status,sort_no,version_no,created_by,updated_by,create_time,update_time) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?, '0', ?,1,?,?,NOW(),NOW())",
                assetNo, limited(meta.get("name"), 120, file.getOriginalFilename()), url, info.mimeType, info.extension,
                info.width, info.height, file.getSize(), limited(meta.get("purpose"), 80, "运营图片"),
                limited(meta.get("pageCode"), 64, "common"), limited(meta.get("moduleCode"), 64, "content"),
                limited(meta.get("recommendedSize"), 64, "按页面设计稿比例"), integer(meta.get("sortNo"), 0),
                currentUserId(), currentUserId());
        Long id = jdbc.queryForObject("SELECT id FROM mall_media_asset WHERE asset_no=?", Long.class, assetNo);
        return jdbc.queryForMap("SELECT id,asset_no AS assetNo,name,url,mime_type AS mimeType,extension,width,height," +
                "size_bytes AS sizeBytes,purpose,page_code AS pageCode,module_code AS moduleCode,recommended_size AS recommendedSize " +
                "FROM mall_media_asset WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> saveModule(Long id, Map<String, Object> body) throws Exception
    {
        Long operator = currentUserId();
        Long assetId = longValue(body.get("imageAssetId"));
        String imageUrl = limited(body.get("imageUrl"), 500, "");
        if (assetId != null)
        {
            List<String> urls = jdbc.query("SELECT url FROM mall_media_asset WHERE id=? AND status='0'", (rs, row) -> rs.getString(1), assetId);
            if (urls.isEmpty()) throw new IllegalArgumentException("所选素材不存在或已停用");
            imageUrl = urls.get(0);
        }
        String configJson = objectMapper.writeValueAsString(body.get("config") instanceof Map ? body.get("config") : Collections.emptyMap());
        if (id == null)
        {
            String moduleKey = limited(body.get("moduleKey"), 100, "");
            if (!moduleKey.matches("^[a-z0-9][a-z0-9._-]{2,99}$")) throw new IllegalArgumentException("模块键格式不正确");
            jdbc.update("INSERT INTO mall_page_module(module_key,page_code,module_code,english_title,title,subtitle,description," +
                    "image_asset_id,image_url,jump_type,jump_target,config_json,status,sort_no,publish_time,version_no,created_by,updated_by,create_time,update_time) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                    moduleKey, limited(body.get("pageCode"),64,"common"), limited(body.get("moduleCode"),64,"content"),
                    limited(body.get("englishTitle"),120,""), limited(body.get("title"),160,""), limited(body.get("subtitle"),255,""),
                    limited(body.get("description"),1000,""), assetId, imageUrl, limited(body.get("jumpType"),32,"none"),
                    limited(body.get("jumpTarget"),255,""), configJson, status(body.get("status")), integer(body.get("sortNo"),0),
                    dateValue(body.get("publishTime")), 1, operator, operator);
            id = jdbc.queryForObject("SELECT id FROM mall_page_module WHERE module_key=?", Long.class, moduleKey);
        }
        else
        {
            Map<String, Object> current = jdbc.queryForMap("SELECT * FROM mall_page_module WHERE id=? FOR UPDATE", id);
            int version = ((Number) current.get("version_no")).intValue();
            jdbc.update("INSERT INTO mall_page_module_history(module_id,version_no,snapshot_json,operator_id,create_time) VALUES(?,?,?,?,NOW())",
                    id, version, objectMapper.writeValueAsString(current), operator);
            jdbc.update("UPDATE mall_page_module SET page_code=?,module_code=?,english_title=?,title=?,subtitle=?,description=?," +
                    "image_asset_id=?,image_url=?,jump_type=?,jump_target=?,config_json=?,status=?,sort_no=?,publish_time=?," +
                    "version_no=version_no+1,updated_by=?,update_time=NOW() WHERE id=?",
                    limited(body.get("pageCode"),64,String.valueOf(current.get("page_code"))),
                    limited(body.get("moduleCode"),64,String.valueOf(current.get("module_code"))),
                    limited(body.get("englishTitle"),120,""), limited(body.get("title"),160,""), limited(body.get("subtitle"),255,""),
                    limited(body.get("description"),1000,""), assetId, imageUrl, limited(body.get("jumpType"),32,"none"),
                    limited(body.get("jumpTarget"),255,""), configJson, status(body.get("status")), integer(body.get("sortNo"),0),
                    dateValue(body.get("publishTime")), operator, id);
        }
        return module(id);
    }

    @Transactional
    public Map<String, Object> restore(Long moduleId, Long historyId) throws Exception
    {
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT snapshot_json AS snapshotJson FROM mall_page_module_history WHERE id=? AND module_id=?", historyId, moduleId);
        Map<String, Object> snapshot = objectMapper.readValue(String.valueOf(history.get("snapshotJson")),
                new TypeReference<Map<String, Object>>(){});
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("pageCode", snapshot.get("page_code")); body.put("moduleCode", snapshot.get("module_code"));
        body.put("englishTitle", snapshot.get("english_title")); body.put("title", snapshot.get("title"));
        body.put("subtitle", snapshot.get("subtitle")); body.put("description", snapshot.get("description"));
        body.put("imageAssetId", snapshot.get("image_asset_id")); body.put("imageUrl", snapshot.get("image_url"));
        body.put("jumpType", snapshot.get("jump_type")); body.put("jumpTarget", snapshot.get("jump_target"));
        body.put("status", snapshot.get("status")); body.put("sortNo", snapshot.get("sort_no"));
        body.put("publishTime", snapshot.get("publish_time"));
        body.put("config", objectMapper.readValue(String.valueOf(snapshot.get("config_json")), new TypeReference<Map<String,Object>>(){}));
        return saveModule(moduleId, body);
    }

    @Transactional
    public void disableMaterial(Long id)
    {
        Integer references = jdbc.queryForObject("SELECT COUNT(*) FROM mall_page_module WHERE image_asset_id=? AND status='0'", Integer.class, id);
        if (references != null && references > 0) throw new IllegalStateException("素材仍被已启用页面模块引用，不能停用");
        jdbc.update("UPDATE mall_media_asset SET status='1',version_no=version_no+1,updated_by=?,update_time=NOW() WHERE id=?", currentUserId(), id);
    }

    private Map<String, Object> module(Long id)
    {
        Map<String, Object> row = jdbc.queryForMap("SELECT id,module_key AS moduleKey,page_code AS pageCode,module_code AS moduleCode," +
                "english_title AS englishTitle,title,subtitle,description,image_asset_id AS imageAssetId,image_url AS imageUrl," +
                "jump_type AS jumpType,jump_target AS jumpTarget,config_json AS configJson,status,sort_no AS sortNo," +
                "publish_time AS publishTime,version_no AS versionNo FROM mall_page_module WHERE id=?", id);
        attachConfig(row); return row;
    }

    private void attachConfig(Map<String, Object> row)
    {
        try { row.put("config", objectMapper.readValue(String.valueOf(row.remove("configJson")), new TypeReference<Map<String,Object>>(){})); }
        catch (Exception error) { row.put("config", Collections.emptyMap()); }
    }

    private ImageInfo validateImage(MultipartFile file) throws Exception
    {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片文件");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片不能超过10MB");
        String extension = FilenameUtils.getExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        String mime = String.valueOf(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension) || !MIME_TYPES.contains(mime)) throw new IllegalArgumentException("仅支持 JPG、PNG、GIF、WebP 图片");
        if ((extension.equals("jpg") || extension.equals("jpeg")) && !mime.equals("image/jpeg")) throw new IllegalArgumentException("图片扩展名与MIME类型不一致");
        if (!extension.equals("jpg") && !extension.equals("jpeg") && !mime.equals("image/" + extension)) throw new IllegalArgumentException("图片扩展名与MIME类型不一致");
        byte[] bytes = file.getBytes();
        if (!signatureMatches(extension, bytes)) throw new IllegalArgumentException("文件内容不是有效图片或与扩展名不符");
        int width; int height;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image != null) { width = image.getWidth(); height = image.getHeight(); }
        else if (extension.equals("webp")) { int[] dimensions = webpDimensions(bytes); width = dimensions[0]; height = dimensions[1]; }
        else throw new IllegalArgumentException("无法读取图片尺寸");
        if (width < 1 || height < 1 || width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE) throw new IllegalArgumentException("图片尺寸无效或超过12000像素");
        return new ImageInfo(extension, mime, width, height);
    }

    private boolean signatureMatches(String extension, byte[] bytes)
    {
        if (bytes.length < 12) return false;
        if (extension.equals("jpg") || extension.equals("jpeg")) return (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255;
        if (extension.equals("png")) return (bytes[0]&255)==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71;
        if (extension.equals("gif")) return new String(bytes,0,6,StandardCharsets.US_ASCII).matches("GIF8[79]a");
        return extension.equals("webp") && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP");
    }

    private int[] webpDimensions(byte[] data)
    {
        String chunk = new String(data, 12, 4, StandardCharsets.US_ASCII);
        if (chunk.equals("VP8X") && data.length >= 30) return new int[]{1+le24(data,24),1+le24(data,27)};
        if (chunk.equals("VP8L") && data.length >= 25)
        {
            int b1=data[21]&255,b2=data[22]&255,b3=data[23]&255,b4=data[24]&255;
            return new int[]{1+(b1|((b2&63)<<8)),1+(((b2&192)>>6)|(b3<<2)|((b4&15)<<10))};
        }
        for (int i=20;i+6<data.length && i<80;i++) if ((data[i]&255)==157 && (data[i+1]&255)==1 && (data[i+2]&255)==42)
            return new int[]{((data[i+3]&255)|((data[i+4]&255)<<8))&16383,((data[i+5]&255)|((data[i+6]&255)<<8))&16383};
        throw new IllegalArgumentException("无法读取WebP图片尺寸");
    }

    private int le24(byte[] b,int i){return (b[i]&255)|((b[i+1]&255)<<8)|((b[i+2]&255)<<16);}
    private Long currentUserId(){try{return SecurityUtils.getUserId();}catch(Exception e){return 0L;}}
    private Long longValue(Object value){if(value==null||String.valueOf(value).trim().isEmpty())return null;return Long.valueOf(String.valueOf(value));}
    private int integer(Object value,int fallback){try{return Integer.parseInt(String.valueOf(value));}catch(Exception e){return fallback;}}
    private Object dateValue(Object value){String text=String.valueOf(value==null?"":value).trim();return text.isEmpty()?null:text;}
    private String status(Object value){return "1".equals(String.valueOf(value))?"1":"0";}
    private String limited(Object value,int max,String fallback){String text=String.valueOf(value==null?fallback:value).trim();if(text.length()>max)throw new IllegalArgumentException("字段长度超过限制");return text;}
    private static class ImageInfo { final String extension,mimeType; final int width,height; ImageInfo(String e,String m,int w,int h){extension=e;mimeType=m;width=w;height=h;} }
}
