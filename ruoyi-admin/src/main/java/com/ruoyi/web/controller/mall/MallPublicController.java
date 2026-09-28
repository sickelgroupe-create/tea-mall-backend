package com.ruoyi.web.controller.mall;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.web.service.mall.MallService;
import com.ruoyi.web.service.mall.MallPageDecorationService;
import com.ruoyi.web.service.mall.MallAuthenticationException;
import com.ruoyi.web.service.mall.MallAuthorizationException;
import com.ruoyi.web.service.mall.MallSessionService;
import com.ruoyi.web.service.mall.MallSmsAuthService;
import com.ruoyi.web.service.mall.MallWechatService;
import com.ruoyi.web.service.mall.MallSessionService.SessionContext;

/** Public storefront API backed by opaque, isolated guest/member sessions. */
@Anonymous
@RestController
@RequestMapping("/mall")
public class MallPublicController
{
    private static final Logger log = LoggerFactory.getLogger(MallPublicController.class);

    @Autowired private MallService mallService;
    @Autowired private MallSessionService sessionService;
    @Autowired private MallSmsAuthService smsAuthService;
    @Autowired private MallWechatService wechatService;
    @Autowired private com.ruoyi.web.service.mall.MallWechatBindingService wechatBindingService;
    @Autowired private MallPageDecorationService decorationService;
    @Autowired private com.ruoyi.web.service.mall.MallSupportContactService supportContactService;

    @GetMapping("/support/contact")
    public AjaxResult supportContact() { return AjaxResult.success(supportContactService.read()); }

    @GetMapping("/health")
    public AjaxResult health()
    {
        return AjaxResult.success(mallService.health());
    }

    @GetMapping("/bootstrap")
	public AjaxResult bootstrap(@RequestHeader(value="X-Mall-Session", required=false) String token,
			@RequestHeader(value="X-Mall-Build-Channel", required=false) String buildChannel)
	{
		SessionContext context = sessionService.resolve(token);
		return sessionPayload(context, buildChannel);
    }

    @GetMapping("/categories")
    public AjaxResult categories()
    {
        return AjaxResult.success(mallService.publicCategories());
    }

    @GetMapping("/products")
    public AjaxResult products(@RequestParam(value="keyword",required=false) String keyword,
            @RequestParam(value="category",required=false) String category,
            @RequestParam(value="sort",required=false,defaultValue="default") String sort,
            @RequestParam(value="minPrice",required=false) BigDecimal minPrice,
            @RequestParam(value="maxPrice",required=false) BigDecimal maxPrice,
            @RequestParam(value="page",required=false,defaultValue="1") int page,
            @RequestParam(value="pageSize",required=false,defaultValue="20") int pageSize)
    {
        return AjaxResult.success(mallService.searchProducts(keyword,category,sort,minPrice,maxPrice,page,pageSize));
    }

    @GetMapping("/products/{productId}")
    public AjaxResult product(@PathVariable Long productId,
            @RequestHeader(value="X-Mall-Session",required=false) String token)
    {
        return AjaxResult.success(mallService.publicProduct(productId,sessionService.resolve(token).getCustomerId()));
    }

    @GetMapping("/topics")
    public AjaxResult topics() { return AjaxResult.success(mallService.publicTopics()); }

    @GetMapping("/page-decorations")
    public AjaxResult pageDecorations() { return AjaxResult.success(decorationService.publicModules()); }

    @GetMapping("/topics/{slug}")
    public AjaxResult topic(@PathVariable String slug) { return AjaxResult.success(mallService.publicTopic(slug)); }

    @GetMapping("/stores/{storeId}")
    public AjaxResult store(@PathVariable Long storeId,
            @RequestHeader(value="X-Mall-Session",required=false) String token)
    {
        return AjaxResult.success(mallService.publicStore(storeId,sessionService.resolve(token).getCustomerId()));
    }

    @PostMapping("/stores/{storeId}/follow")
    public AjaxResult followStore(@PathVariable Long storeId,
            @RequestHeader(value="X-Mall-Session",required=false) String token)
    {
        mallService.followStore(member(token),storeId,true);
        return AjaxResult.success();
    }

    @DeleteMapping("/stores/{storeId}/follow")
    public AjaxResult unfollowStore(@PathVariable Long storeId,
            @RequestHeader(value="X-Mall-Session",required=false) String token)
    {
        mallService.followStore(member(token),storeId,false);
        return AjaxResult.success();
    }

    @PostMapping("/session/login")
    public AjaxResult login(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        SessionContext context = sessionService.login(token, string(body.get("phone")),
                string(body.get("password")), IpUtils.getIpAddr());
        return sessionPayload(context);
    }

    @PostMapping("/session/register")
    public AjaxResult register(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        String phone = string(body.get("phone"));
        smsAuthService.verifyAndConsume(phone, "REGISTER", string(body.get("code")), null);
        SessionContext context = sessionService.register(token, phone,
                string(body.get("password")), string(body.get("refCode")));
        return sessionPayload(context);
    }

    @PostMapping("/session/sms/request")
    public AjaxResult requestSmsCode(
            @RequestHeader(value="X-Mall-Build-Channel", required=false) String buildChannel,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(smsAuthService.requestCode(
                string(body.get("phone")), string(body.get("purpose")), buildChannel));
    }

    @PostMapping("/session/code-login")
    public AjaxResult codeLogin(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestHeader(value="X-Mall-Build-Channel", required=false) String buildChannel,
            @RequestBody Map<String, Object> body)
    {
        String phone = string(body.get("phone"));
        smsAuthService.verifyAndConsume(phone, "LOGIN", string(body.get("code")), buildChannel);
        return sessionPayload(sessionService.loginWithVerifiedPhone(token, phone));
    }

    @PostMapping("/session/password/reset")
    public AjaxResult resetPassword(@RequestBody Map<String, Object> body)
    {
        String phone = string(body.get("phone"));
        smsAuthService.verifyAndConsume(phone, "RESET", string(body.get("code")));
        sessionService.resetPasswordWithVerifiedPhone(phone, string(body.get("newPassword")));
        return AjaxResult.success();
    }

    @GetMapping("/wechat/config")
    public AjaxResult wechatConfig()
    {
        return AjaxResult.success(wechatService.publicConfig());
    }

    @PostMapping("/session/wechat")
    public AjaxResult wechatLogin(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        MallWechatService.WechatIdentity identity = wechatService.exchangeMiniProgramCode(string(body.get("code")));
        if (!sessionService.isWechatIdentityBound(identity.getAppId(), identity.getOpenId()))
        {
            String ticket = sessionService.createWechatLoginTicket(identity.getAppId(), identity.getOpenId(),
                    identity.getUnionId(), string(body.get("refCode")));
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("wechatBindingRequired", true);
            data.put("wechatTicket", ticket);
            data.put("expiresInSeconds", 600);
            return AjaxResult.success(data);
        }
        SessionContext context = sessionService.loginWithWechat(token, identity.getAppId(), identity.getOpenId(),
                identity.getUnionId(), string(body.get("refCode")));
        return sessionPayload(context);
    }

    @PostMapping("/session/wechat/register")
    public AjaxResult wechatRegister(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return sessionPayload(sessionService.registerWithWechatTicket(token, string(body.get("wechatTicket")),
                string(body.get("nickname"))));
    }

    @PostMapping("/session/wechat/bind-phone")
    public AjaxResult wechatBindPhone(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        String phone = string(body.get("phone"));
        smsAuthService.verifyAndConsume(phone, "BIND_WECHAT", string(body.get("code")));
        return sessionPayload(sessionService.bindWechatTicketToPhone(token, string(body.get("wechatTicket")), phone));
    }

    @PostMapping("/session/logout")
    public AjaxResult logout(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        SessionContext context = sessionService.logout(token);
        return sessionPayload(context);
    }

    @GetMapping("/customers/wechat")
    public AjaxResult wechatBinding(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(wechatBindingService.status(member(token)));
    }

    @PostMapping("/customers/wechat/bind")
    public AjaxResult bindCurrentWechat(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String,Object> body)
    {
        Long customerId = member(token);
        return AjaxResult.success(wechatBindingService.bindCurrent(customerId, string(body.get("code"))));
    }

    @PutMapping("/customers/profile")
    public AjaxResult updateProfile(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.updateProfile(member(token), body));
    }

    @PostMapping("/customers/phone/change")
    public AjaxResult changePhone(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        Long customerId = member(token);
        String phone = string(body.get("phone"));
        smsAuthService.verifyAndConsume(phone, "CHANGE_PHONE", string(body.get("code")), null);
        return AjaxResult.success(sessionService.changePhone(customerId, phone));
    }

    @PutMapping("/customers/password")
    public AjaxResult updatePassword(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        mallService.updatePassword(member(token), body);
        return AjaxResult.success();
    }

    @PostMapping("/customers/avatar")
    public AjaxResult uploadAvatar(@RequestHeader(value="X-Mall-Session", required=false) String token,
            MultipartFile file) throws Exception
    {
        Long customerId = member(token);
        if (file == null || file.isEmpty())
            return AjaxResult.error(HttpStatus.BAD_REQUEST.value(), "请选择头像图片");
        if (file.getSize() > 5L * 1024L * 1024L)
            return AjaxResult.error(HttpStatus.BAD_REQUEST.value(), "头像图片不能超过5MB");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        byte[] bytes = file.getBytes();
        boolean jpeg = bytes.length > 2 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8;
        boolean png = bytes.length > 7 && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47;
        boolean gif = bytes.length > 5 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
        boolean webp = bytes.length > 11 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        if (!(type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/gif")
                || type.equals("image/webp")) || !(jpeg || png || gif || webp))
            return AjaxResult.error(HttpStatus.BAD_REQUEST.value(), "仅支持真实的 JPG、PNG、GIF 或 WEBP 图片");
        String fileName = FileUploadUtils.upload(RuoYiConfig.getUploadPath(), file,
                MimeTypeUtils.IMAGE_EXTENSION);
        return AjaxResult.success(mallService.updateAvatar(customerId, fileName));
    }

    @GetMapping("/orders/{orderNo}/logistics")
    public AjaxResult logistics(@PathVariable String orderNo, @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.logistics(member(token), orderNo));
    }

    @PutMapping("/cart/{productId}")
    public AjaxResult cart(@PathVariable Long productId, @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        mallService.updateCart(sessionService.resolve(token).getCustomerId(), productId, longValue(body.get("skuId")),
                integer(body.get("qty"), 1), bool(body.get("checked"), true));
        return AjaxResult.success();
    }

    @PostMapping("/cart/remove")
    public AjaxResult removeCart(@RequestHeader(value="X-Mall-Session", required=false) String token, @RequestBody Map<String, Object> body)
    {
        Object productIds = body.get("productIds");
        Object skuIds = body.get("skuIds");
        mallService.removeCart(sessionService.resolve(token).getCustomerId(),
                skuIds instanceof List ? (List<?>) skuIds : Collections.emptyList(),
                productIds instanceof List ? (List<?>) productIds : Collections.emptyList());
        return AjaxResult.success();
    }

    @PostMapping("/orders")
	public AjaxResult createOrder(@RequestHeader(value="X-Mall-Session", required=false) String token,
			@RequestHeader(value="X-Mall-Build-Channel", required=false) String buildChannel,
			@RequestBody Map<String, Object> body)
	{
		return AjaxResult.success(mallService.createOrder(member(token), body, buildChannel));
    }

    @PostMapping("/orders/quote")
    public AjaxResult checkoutQuote(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.checkoutQuote(member(token), body));
    }

    @GetMapping("/orders/{orderNo}")
    public AjaxResult orderDetail(@PathVariable String orderNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.orderDetail(member(token), orderNo));
    }

    @PostMapping("/orders/{orderNo}/test-pay")
	public AjaxResult testPay(@PathVariable String orderNo,
			@RequestHeader(value="X-Mall-Session", required=false) String token,
			@RequestHeader(value="X-Mall-Build-Channel", required=false) String buildChannel,
			@RequestBody Map<String, Object> body)
	{
		if ("failure".equals(string(body.get("outcome"))))
			return AjaxResult.success(mallService.testPayFailure(member(token), orderNo,
					string(body.get("requestNo")), buildChannel));
		return AjaxResult.success(mallService.testPay(member(token), orderNo,
				string(body.get("requestNo")), buildChannel));
    }

    @PutMapping("/orders/{orderNo}/status")
    public AjaxResult orderStatus(@PathVariable String orderNo, @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        mallService.updatePublicOrderStatus(member(token), orderNo, string(body.get("status")));
        return AjaxResult.success();
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancelOrder(@PathVariable String orderNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.cancelOrder(member(token), orderNo, body));
    }

    @PostMapping("/orders/{orderNo}/expedite")
    public AjaxResult expediteOrder(@PathVariable String orderNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.expediteOrder(member(token), orderNo, body));
    }

    @DeleteMapping("/orders/{orderNo}")
    public AjaxResult deleteOrder(@PathVariable String orderNo, @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.deleteOrder(member(token), orderNo);
        return AjaxResult.success();
    }

    @PostMapping("/aftersales")
    public AjaxResult aftersale(@RequestHeader(value="X-Mall-Session", required=false) String token, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.submitAftersale(member(token), body));
    }

    @PostMapping("/aftersales/quote")
    public AjaxResult aftersaleQuote(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.aftersaleQuote(member(token), body));
    }

    @GetMapping("/aftersales/{aftersaleNo}")
    public AjaxResult aftersaleDetail(@PathVariable String aftersaleNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.aftersaleDetail(member(token), aftersaleNo));
    }

    @PostMapping("/aftersales/{aftersaleNo}/cancel")
    public AjaxResult cancelAftersale(@PathVariable String aftersaleNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.cancelAftersale(member(token), aftersaleNo, body));
    }

    @PostMapping("/aftersales/{aftersaleNo}/return-logistics")
    public AjaxResult returnLogistics(@PathVariable String aftersaleNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.submitReturnLogistics(member(token), aftersaleNo, body));
    }

    @PostMapping("/aftersales/{aftersaleNo}/confirm-exchange")
    public AjaxResult confirmExchange(@PathVariable String aftersaleNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.confirmExchangeReceipt(member(token), aftersaleNo, body));
    }

    @PostMapping("/addresses")
    public AjaxResult saveAddress(@RequestHeader(value="X-Mall-Session", required=false) String token, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.saveAddress(member(token), body));
    }

    @DeleteMapping("/addresses/{addressId}")
    public AjaxResult deleteAddress(@PathVariable Long addressId, @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.deleteAddress(member(token), addressId);
        return AjaxResult.success();
    }

    @PostMapping("/exchanges")
    public AjaxResult exchange(@RequestHeader(value="X-Mall-Session", required=false) String token, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.exchange(member(token), body));
    }

    @PostMapping("/checkin")
    public AjaxResult checkin(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.checkin(member(token)));
    }

    @GetMapping("/points/overview")
    public AjaxResult pointsOverview(@RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.pointsOverview(member(token))); }

    @GetMapping("/points/logs")
    public AjaxResult pointsLogs(@RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.pointsLogs(member(token))); }

    @GetMapping("/points/tasks")
    public AjaxResult pointsTasks(@RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.pointsTasks(member(token))); }

    @PostMapping("/points/tasks/{ruleId}/claim")
    public AjaxResult claimPointsTask(@PathVariable Long ruleId,
            @RequestHeader(value="X-Mall-Session", required=false) String token,@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.claimPointsTask(member(token),ruleId,body)); }

    @GetMapping("/points/tiers")
    public AjaxResult tierRewards(@RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.tierRewards(member(token))); }

    @GetMapping("/points/tiers/{ruleId}")
    public AjaxResult tierRewardDetail(@PathVariable Long ruleId,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.tierRewardDetail(member(token),ruleId)); }

    @PostMapping("/points/tiers/{ruleId}/claim")
    public AjaxResult claimTierReward(@PathVariable Long ruleId,
            @RequestHeader(value="X-Mall-Session", required=false) String token,@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.claimTierReward(member(token),ruleId,body)); }

    @GetMapping("/points/reward-details")
    public AjaxResult rewardDetails(@RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.rewardDetails(member(token))); }

    @GetMapping("/exchanges/{exchangeNo}")
    public AjaxResult exchangeDetail(@PathVariable String exchangeNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.exchangeDetail(member(token),exchangeNo)); }

    @PostMapping("/exchanges/{exchangeNo}/cancel")
    public AjaxResult cancelExchange(@PathVariable String exchangeNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.cancelExchange(member(token),exchangeNo)); }

    @PostMapping("/exchanges/{exchangeNo}/confirm-receipt")
    public AjaxResult confirmExchangeReceipt(@PathVariable String exchangeNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    { return AjaxResult.success(mallService.confirmExchangeReceipt(member(token), exchangeNo)); }

    @PostMapping("/favorites/{productId}")
    public AjaxResult addFavorite(@PathVariable Long productId, @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.addFavorite(member(token), productId);
        return AjaxResult.success();
    }

    @DeleteMapping("/favorites/{productId}")
    public AjaxResult removeFavorite(@PathVariable Long productId, @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.removeFavorite(member(token), productId);
        return AjaxResult.success();
    }

    @PostMapping("/topics/{topicId}/favorite")
    public AjaxResult addTopicFavorite(@PathVariable Long topicId,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.addTopicFavorite(member(token), topicId);
        return AjaxResult.success();
    }

    @DeleteMapping("/topics/{topicId}/favorite")
    public AjaxResult removeTopicFavorite(@PathVariable Long topicId,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.removeTopicFavorite(member(token), topicId);
        return AjaxResult.success();
    }

    @GetMapping("/notifications")
    public AjaxResult notifications(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.listNotifications(member(token)));
    }

    @PutMapping("/notifications/read-all")
    public AjaxResult readAllNotifications(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.markAllNotificationsRead(member(token));
        return AjaxResult.success();
    }

    @PutMapping("/notifications/{id}/read")
    public AjaxResult readNotification(@PathVariable Long id,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        mallService.markNotificationRead(member(token), id);
        return AjaxResult.success();
    }

    @PostMapping("/distribution/withdrawals")
    public AjaxResult requestWithdrawal(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.requestWithdrawal(member(token), body));
    }

    @PostMapping("/distribution/bind")
    public AjaxResult bindReferral(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        sessionService.bindReferral(member(token), string(body.get("sceneCode")));
        return AjaxResult.success();
    }

    @GetMapping("/distribution/friends")
    public AjaxResult teaFriends(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestParam(value="level", required=false) Integer level)
    {
        return AjaxResult.success(mallService.teaFriends(member(token), level));
    }

    @GetMapping("/distribution/friends/{friendId}")
    public AjaxResult teaFriend(@PathVariable Long friendId,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.teaFriendDetail(member(token), friendId));
    }

    @GetMapping("/distribution/friends/{friendId}/orders")
    public AjaxResult teaFriendOrders(@PathVariable Long friendId,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.teaFriendOrders(member(token), friendId));
    }

    @GetMapping("/distribution/invite-records")
    public AjaxResult inviteRecords(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.inviteRecords(member(token)));
    }

    @GetMapping("/distribution/invite-scene")
    public AjaxResult inviteScene(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.inviteScene(member(token)));
    }

    @GetMapping("/distribution/invite-scene/mini-code")
    public ResponseEntity<byte[]> inviteMiniProgramCode(
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        byte[] png = mallService.inviteMiniProgramCode(member(token));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl("private,max-age=3600");
        return new ResponseEntity<byte[]>(png, headers, HttpStatus.OK);
    }

    @GetMapping("/distribution/invite-gift")
    public AjaxResult inviteGift(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.inviteGift(member(token)));
    }

    @PostMapping("/distribution/invite-gift/claim")
    public AjaxResult claimInviteGift(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.claimInviteGift(member(token), body));
    }

    @PostMapping("/service-tickets")
    public AjaxResult submitServiceTicket(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.submitServiceTicket(member(token), body));
    }

    @GetMapping("/service-tickets")
    public AjaxResult serviceTickets(@RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.serviceTickets(member(token)));
    }

    @GetMapping("/service-tickets/{ticketNo}")
    public AjaxResult serviceTicket(@PathVariable String ticketNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token)
    {
        return AjaxResult.success(mallService.serviceTicketDetail(member(token), ticketNo));
    }

    @PostMapping("/service-tickets/{ticketNo}/messages")
    public AjaxResult replyServiceTicket(@PathVariable String ticketNo,
            @RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.replyServiceTicket(member(token), ticketNo, body));
    }

    @PostMapping("/reviews")
    public AjaxResult submitReview(@RequestHeader(value="X-Mall-Session", required=false) String token,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(mallService.submitReview(member(token), body));
    }

    @ExceptionHandler(MallAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public AjaxResult authenticationFailure(MallAuthenticationException error)
    {
        return AjaxResult.error(HttpStatus.UNAUTHORIZED.value(), error.getMessage());
    }

    @ExceptionHandler(MallAuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxResult forbidden(MallAuthorizationException error)
    {
        log.warn("商城权限校验失败: {}", error.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN.value(), error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxResult badRequest(IllegalArgumentException error)
    {
        log.warn("商城请求参数处理失败", error);
        return AjaxResult.error(HttpStatus.BAD_REQUEST.value(), error.getMessage());
    }

    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public AjaxResult concurrentRequestConflict(TransientDataAccessException error)
    {
        log.warn("商城并发请求发生数据库锁冲突，事务已回滚", error);
        return AjaxResult.error(HttpStatus.CONFLICT.value(), "请求冲突，本次操作已回滚，请刷新后重试");
    }

	private AjaxResult sessionPayload(SessionContext context)
	{
		return sessionPayload(context, null);
	}

	private AjaxResult sessionPayload(SessionContext context, String buildChannel)
	{
        Map<String, Object> data = mallService.bootstrap(context.getCustomerId(), context.isAuthenticated());
        data.put("pageDecorations", decorationService.publicModules());
        data.put("sessionToken", context.getSessionToken());
        data.put("sessionExpiresInSeconds", 30L * 24L * 60L * 60L);
        data.put("sessionExpired", context.isExpired());
        data.put("testMode", sessionService.isTestMode());
		data.put("testPaymentEnabled", context.isAuthenticated()
				&& mallService.testPaymentAvailable(context.getCustomerId(), buildChannel));
        return AjaxResult.success(data);
    }

    private Long member(String token) { return sessionService.requireMember(token).getCustomerId(); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private int integer(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)); }
    private Long longValue(Object value) { try { return value == null || String.valueOf(value).trim().isEmpty() ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
}
