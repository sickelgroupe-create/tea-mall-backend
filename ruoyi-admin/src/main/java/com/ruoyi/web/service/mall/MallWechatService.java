package com.ruoyi.web.service.mall;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Server-side WeChat mini-program code exchange. The AppSecret never reaches a client. */
@Service
public class MallWechatService
{
    @Value("${mall.wechat.app-id:}")
    private String appId;

    @Value("${mall.wechat.app-secret:}")
    private String appSecret;

    @Value("${mall.wechat.code-env-version:trial}")
    private String codeEnvVersion;

    public String appId() { return appId == null ? "" : appId.trim(); }

    public String codeEnvVersion()
    {
        String value = codeEnvVersion == null ? "trial" : codeEnvVersion.trim();
        if (!java.util.Arrays.asList("develop", "trial", "release").contains(value))
            throw new IllegalArgumentException("小程序码版本配置无效");
        return value;
    }

    private final RestTemplate restTemplate;
    private volatile String accessToken = "";
    private volatile long accessTokenExpiresAt = 0L;

    public MallWechatService()
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> publicConfig()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", configured());
        result.put("platform", "mp-weixin");
        result.put("appId", configured() ? appId.trim() : "");
        return result;
    }

    public WechatIdentity exchangeMiniProgramCode(String code)
    {
        if (!configured()) throw new IllegalArgumentException("微信登录尚未完成服务端配置");
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("微信授权码不能为空");
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                .queryParam("appid", appId.trim())
                .queryParam("secret", appSecret.trim())
                .queryParam("js_code", code.trim())
                .queryParam("grant_type", "authorization_code")
                .build(true).toUri();
        JSONObject response;
        try
        {
            response = JSON.parseObject(restTemplate.getForObject(uri, String.class));
        }
        catch (Exception error)
        {
            throw new IllegalArgumentException("微信服务暂时不可用，请稍后重试");
        }
        Integer errorCode = response.getInteger("errcode");
        if (errorCode != null && errorCode != 0)
        {
            if (errorCode == 40029 || errorCode == 40163) throw new IllegalArgumentException("微信授权已过期，请重新登录");
            if (errorCode == 40125 || errorCode == 40013) throw new IllegalArgumentException("微信应用配置无效，请联系管理员");
            throw new IllegalArgumentException("微信登录失败（" + errorCode + "）");
        }
        String openId = response.getString("openid");
        if (openId == null || openId.trim().isEmpty()) throw new IllegalArgumentException("微信未返回用户标识");
        return new WechatIdentity(appId.trim(), openId.trim(), response.getString("unionid"));
    }

    public byte[] createUnlimitedMiniProgramCode(String scene)
    {
        if (!configured()) throw new IllegalArgumentException("微信小程序码尚未完成服务端配置");
        String normalizedScene = scene == null ? "" : scene.trim();
        if (!normalizedScene.matches("^[A-Za-z0-9_-]{1,32}$")) throw new IllegalArgumentException("邀请场景参数无效");
        String token = accessToken();
        String url = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + token;
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("scene", normalizedScene);
        body.put("page", "pages/login/login");
        body.put("check_path", false);
        body.put("env_version", codeEnvVersion());
        body.put("width", 430);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response;
        try
        {
            response = restTemplate.postForEntity(url, new HttpEntity<String>(JSON.toJSONString(body), headers), byte[].class);
        }
        catch (Exception error)
        {
            throw new IllegalArgumentException("微信小程序码生成服务暂时不可用");
        }
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("微信未返回小程序码");
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && MediaType.APPLICATION_JSON.includes(contentType))
        {
            JSONObject error = JSON.parseObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            throw new IllegalArgumentException("微信小程序码生成失败（" + error.getIntValue("errcode") + "）");
        }
        return bytes;
    }

    private synchronized String accessToken()
    {
        long now = System.currentTimeMillis();
        if (!accessToken.isEmpty() && now < accessTokenExpiresAt) return accessToken;
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/cgi-bin/token")
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", appId.trim())
                .queryParam("secret", appSecret.trim()).build(true).toUri();
        JSONObject response;
        try
        {
            response = JSON.parseObject(restTemplate.getForObject(uri, String.class));
        }
        catch (Exception error)
        {
            throw new IllegalArgumentException("微信服务访问令牌获取失败");
        }
        String token = response.getString("access_token");
        if (token == null || token.trim().isEmpty())
            throw new IllegalArgumentException("微信服务访问令牌获取失败（" + response.getIntValue("errcode") + "）");
        int expiresIn = Math.max(300, response.getIntValue("expires_in"));
        accessToken = token.trim();
        accessTokenExpiresAt = now + Math.max(60, expiresIn - 300) * 1000L;
        return accessToken;
    }

    public boolean configured()
    {
        return appId != null && !appId.trim().isEmpty() && appSecret != null && !appSecret.trim().isEmpty();
    }

    public static class WechatIdentity
    {
        private final String appId;
        private final String openId;
        private final String unionId;

        WechatIdentity(String appId, String openId, String unionId)
        {
            this.appId = appId;
            this.openId = openId;
            this.unionId = unionId == null ? "" : unionId;
        }

        public String getAppId() { return appId; }
        public String getOpenId() { return openId; }
        public String getUnionId() { return unionId; }
    }
}
