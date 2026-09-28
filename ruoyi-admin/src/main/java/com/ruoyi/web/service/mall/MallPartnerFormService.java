package com.ruoyi.web.service.mall;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class MallPartnerFormService {
 @Autowired private JdbcTemplate jdbc;
 public Map<String,Object> config(){
  Map<String,Object> c=jdbc.queryForMap("SELECT title,intro,submit_text AS submitText,footer_text AS footerText,fields_json AS fieldsJson,version_no AS versionNo FROM mall_partner_form_config WHERE id=1");
  c.put("fields",JSON.parseArray(String.valueOf(c.remove("fieldsJson"))));return c;
 }
 @Transactional public void save(Map<String,Object> body,Long operator){
  jdbc.queryForMap("SELECT id FROM mall_partner_form_config WHERE id=1 FOR UPDATE");
  Map<String,Object> before=config();
  if(!String.valueOf(before.get("versionNo")).equals(String.valueOf(body.get("versionNo"))))throw new IllegalArgumentException("配置已被更新，请刷新后重试");
  if(!(body.get("fields") instanceof List))throw new IllegalArgumentException("缺少申请字段配置");
  List<Map<String,Object>> fields=new ArrayList<>();
  List<?> incoming=(List<?>)body.get("fields");
  for(Object value:(List<?>)before.get("fields")){
   Map<String,Object> original=(Map<String,Object>)value;
   Map<String,Object> change=null;
   for(Object candidate:incoming)if(candidate instanceof Map && original.get("key").equals(((Map<?,?>)candidate).get("key"))){if(change!=null)throw new IllegalArgumentException("申请字段重复");change=(Map<String,Object>)candidate;}
   if(change==null)throw new IllegalArgumentException("申请字段配置不完整");
   Map<String,Object> field=new LinkedHashMap<>(original);
   field.put("label",plain(change.get("label"),30,false));field.put("placeholder",plain(change.get("placeholder"),100,true));
   if(!(change.get("required") instanceof Boolean))throw new IllegalArgumentException("必填设置格式不正确");
   field.put("required",change.get("required"));fields.add(field);
  }
  if(incoming.size()!=fields.size())throw new IllegalArgumentException("不支持的申请字段");
  jdbc.update("UPDATE mall_partner_form_config SET title=?,intro=?,submit_text=?,footer_text=?,fields_json=?,version_no=version_no+1,update_time=NOW() WHERE id=1",plain(body.get("title"),60,false),plain(body.get("intro"),500,true),plain(body.get("submitText"),20,false),plain(body.get("footerText"),100,true),JSON.toJSONString(fields));
  jdbc.update("INSERT INTO mall_partner_form_audit(operator_id,before_json,after_json) VALUES(?,?,?)",operator,JSON.toJSONString(before),JSON.toJSONString(config()));
 }
 public Map<String,Object> validate(Map<String,Object> body){
  Map<String,Object> out=new LinkedHashMap<>();
  for(Object item:(List<?>)config().get("fields")){
   Map<String,Object> f=(Map<String,Object>)item;String key=String.valueOf(f.get("key")),v=String.valueOf(body.getOrDefault(key,"")).trim();
   if(body.get(key)==null)v="";
   if(v.isEmpty()&&!Boolean.TRUE.equals(f.get("required"))){out.put(key,"");continue;}
   int min=((Number)f.get("min")).intValue(),max=((Number)f.get("max")).intValue();
   if(v.length()<min||v.length()>max)throw new IllegalArgumentException(f.get("label")+"长度需为"+min+"-"+max+"个字符");
   if("idNo".equals(key)&&!v.matches("(?:\\d{15}|\\d{17}[0-9Xx])"))throw new IllegalArgumentException("身份证号码格式不正确");
   if("phone".equals(key)&&!v.matches("1[3-9]\\d{9}"))throw new IllegalArgumentException("联系电话格式不正确");
   out.put(key,v);
  }return out;
 }
 static String plain(Object value,int max,boolean optional){String s=value==null?"":String.valueOf(value).trim();if((!optional&&s.isEmpty())||s.length()>max||s.matches("(?s).*[<>\\p{Cntrl}].*"))throw new IllegalArgumentException("请填写长度合适的纯文本，不支持HTML及控制字符");return s;}
}
