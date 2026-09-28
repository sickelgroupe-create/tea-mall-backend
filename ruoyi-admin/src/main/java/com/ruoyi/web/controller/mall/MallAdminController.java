package com.ruoyi.web.controller.mall;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallService;
import com.ruoyi.web.service.mall.MallSupportContactService;
import com.ruoyi.common.utils.SecurityUtils;

/** Authenticated administration endpoints for the shared mall data. */
@RestController
@RequestMapping("/mall/admin")
public class MallAdminController
{
    @Autowired
    private MallService mallService;
    @PreAuthorize("@ss.hasPermi('mall:points-task:list')") @GetMapping("/points/consumption-config") public AjaxResult consumptionConfig(){return AjaxResult.success(mallService.consumptionPointsConfig());}
    @PreAuthorize("@ss.hasPermi('mall:points-task:edit')") @PutMapping("/points/consumption-config") public AjaxResult saveConsumptionConfig(@RequestBody Map<String,Object>b){mallService.saveConsumptionPointsConfig(b);return AjaxResult.success();}

    @Autowired private MallSupportContactService supportContactService;

    @PreAuthorize("@ss.hasPermi('mall:ticket:list')")
    @GetMapping("/support/contact")
    public AjaxResult supportContact() { return AjaxResult.success(supportContactService.read()); }

    @PreAuthorize("@ss.hasPermi('mall:ticket:edit')")
    @PutMapping("/support/contact")
    public AjaxResult saveSupportContact(@RequestBody Map<String,Object> body) {
        if (!body.containsKey("phone") || !(body.get("phone") instanceof String))
            throw new IllegalArgumentException("请提交客服电话，清空号码请提交空字符串");
        return AjaxResult.success(supportContactService.save((String)body.get("phone"), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasAnyPermi('mall:product:list,mall:order:list,mall:customer:list')")
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return AjaxResult.success(mallService.overview());
    }

    @PreAuthorize("@ss.hasPermi('mall:product:list')")
    @GetMapping("/products")
    public AjaxResult products() { return AjaxResult.success(mallService.adminProducts()); }

	@PreAuthorize("@ss.hasPermi('mall:product:edit')")
	@PostMapping("/products")
	public AjaxResult createProduct(@RequestBody Map<String,Object> body)
	{
		return AjaxResult.success(mallService.adminCreateProduct(body));
	}

    @PreAuthorize("@ss.hasPermi('mall:product:edit')")
    @PutMapping("/products/{id}")
    public AjaxResult updateProduct(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateProduct(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:product:remove')")
    @DeleteMapping("/products/{id}")
    public AjaxResult deleteProduct(@PathVariable Long id)
    {
        mallService.adminDeleteProduct(id); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:category:list')")
    @GetMapping("/categories")
    public AjaxResult categories() { return AjaxResult.success(mallService.adminCategories()); }

    @PreAuthorize("@ss.hasPermi('mall:category:edit')")
    @PostMapping("/categories")
    public AjaxResult createCategory(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateCategory(body)); }

    @PreAuthorize("@ss.hasPermi('mall:category:edit')")
    @PutMapping("/categories/{id}")
    public AjaxResult updateCategory(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateCategory(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:category:remove')")
    @DeleteMapping("/categories/{id}")
    public AjaxResult deleteCategory(@PathVariable Long id)
    { mallService.adminDeleteCategory(id); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:topic:list')")
    @GetMapping("/topics")
    public AjaxResult topics() { return AjaxResult.success(mallService.adminTopics()); }

    @PreAuthorize("@ss.hasPermi('mall:topic:edit')")
    @PostMapping("/topics")
    public AjaxResult createTopic(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateTopic(body)); }

    @PreAuthorize("@ss.hasPermi('mall:topic:edit')")
    @PutMapping("/topics/{id}")
    public AjaxResult updateTopic(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateTopic(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:topic:remove')")
    @DeleteMapping("/topics/{id}")
    public AjaxResult deleteTopic(@PathVariable Long id)
    { mallService.adminDeleteTopic(id); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:store:list')")
    @GetMapping("/stores")
    public AjaxResult stores() { return AjaxResult.success(mallService.adminStores()); }

    @PreAuthorize("@ss.hasPermi('mall:store:edit')")
    @PostMapping("/stores")
    public AjaxResult createStore(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateStore(body)); }

    @PreAuthorize("@ss.hasPermi('mall:store:edit')")
    @PutMapping("/stores/{id}")
    public AjaxResult updateStore(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateStore(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:store:remove')")
    @DeleteMapping("/stores/{id}")
    public AjaxResult deleteStore(@PathVariable Long id)
    { mallService.adminDeleteStore(id); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:sku:list')")
    @GetMapping("/skus")
    public AjaxResult skus() { return AjaxResult.success(mallService.adminSkus()); }

    @PreAuthorize("@ss.hasPermi('mall:sku:edit')")
    @PostMapping("/skus")
    public AjaxResult createSku(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateSku(body)); }

    @PreAuthorize("@ss.hasPermi('mall:sku:edit')")
    @PutMapping("/skus/{id}")
    public AjaxResult updateSku(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateSku(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:sku:remove')")
    @DeleteMapping("/skus/{id}")
    public AjaxResult deleteSku(@PathVariable Long id)
    { mallService.adminDeleteSku(id); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:order:list')")
    @GetMapping("/orders")
    public AjaxResult orders() { return AjaxResult.success(mallService.adminOrders()); }

    @PreAuthorize("@ss.hasPermi('mall:order:edit')")
    @PutMapping("/orders/{id}")
    public AjaxResult updateOrder(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateOrder(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:order:list')")
    @GetMapping("/order-expedites")
    public AjaxResult orderExpedites() { return AjaxResult.success(mallService.adminOrderExpedites()); }

    @PreAuthorize("@ss.hasPermi('mall:order:edit')")
    @PutMapping("/order-expedites/{id}")
    public AjaxResult updateOrderExpedite(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateOrderExpedite(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:aftersale:list')")
    @GetMapping("/aftersales")
    public AjaxResult aftersales() { return AjaxResult.success(mallService.adminAftersales()); }

    @PreAuthorize("@ss.hasPermi('mall:aftersale:edit')")
    @PutMapping("/aftersales/{id}")
    public AjaxResult updateAftersale(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateAftersale(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:notification:list')")
    @GetMapping("/notifications")
    public AjaxResult notifications() { return AjaxResult.success(mallService.adminNotifications()); }

    @PreAuthorize("@ss.hasPermi('mall:notification:edit')")
    @PostMapping("/notifications")
    public AjaxResult createNotification(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateNotification(body)); }

    @PreAuthorize("@ss.hasPermi('mall:notification:edit')")
    @PutMapping("/notifications/{id}")
    public AjaxResult updateNotification(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateNotification(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:notification:remove')")
    @DeleteMapping("/notifications/{id}")
    public AjaxResult deleteNotification(@PathVariable Long id)
    { mallService.adminDeleteNotification(id); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:reward:list')")
    @GetMapping("/rewards")
    public AjaxResult rewards() { return AjaxResult.success(mallService.adminRewards()); }

    @PreAuthorize("@ss.hasPermi('mall:reward:edit')")
    @PostMapping("/rewards")
    public AjaxResult createReward(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateReward(body)); }

    @PreAuthorize("@ss.hasPermi('mall:reward:edit')")
    @PutMapping("/rewards/{id}")
    public AjaxResult updateReward(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateReward(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:points-task:list')")
    @GetMapping("/points/tasks")
    public AjaxResult pointsTasks() { return AjaxResult.success(mallService.adminPointsTasks()); }

    @PreAuthorize("@ss.hasPermi('mall:points-task:edit')")
    @PutMapping("/points/tasks/{id}")
    public AjaxResult updatePointsTask(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdatePointsTask(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:points-ledger:list')")
    @GetMapping("/points/ledger")
    public AjaxResult pointsLedger() { return AjaxResult.success(mallService.adminPointsLedger()); }

    @PreAuthorize("@ss.hasPermi('mall:points-ledger:list')")
    @GetMapping("/points/accruals")
    public AjaxResult pointsAccruals() { return AjaxResult.success(mallService.adminPointsAccruals()); }

    @PreAuthorize("@ss.hasPermi('mall:points-ledger:list')")
    @GetMapping("/points/debts")
    public AjaxResult pointsDebts() { return AjaxResult.success(mallService.adminPointsDebts()); }

    @PreAuthorize("@ss.hasPermi('mall:points-ledger:list')")
    @GetMapping("/points/customers")
    public AjaxResult pointCustomers() { return AjaxResult.success(mallService.adminPointCustomers()); }

    @PreAuthorize("@ss.hasPermi('mall:tier-reward:list')")
    @GetMapping("/points/tiers")
    public AjaxResult tierRules() { return AjaxResult.success(mallService.adminTierRules()); }

    @PreAuthorize("@ss.hasPermi('mall:tier-reward:edit')")
    @PutMapping("/points/tiers/{id}")
    public AjaxResult updateTierRule(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateTierRule(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:tier-reward:list')")
    @GetMapping("/points/tier-claims")
    public AjaxResult tierClaims() { return AjaxResult.success(mallService.adminTierClaims()); }

    @PreAuthorize("@ss.hasPermi('mall:customer:list')")
    @GetMapping("/customers")
    public AjaxResult customers() { return AjaxResult.success(mallService.adminCustomers()); }

    @PreAuthorize("@ss.hasPermi('mall:customer:edit')")
    @PutMapping("/customers/{id}")
    public AjaxResult updateCustomer(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateCustomer(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:distribution:list')")
    @GetMapping("/distributors")
    public AjaxResult distributors() { return AjaxResult.success(mallService.adminDistributors()); }

    @PreAuthorize("@ss.hasPermi('mall:invite:list')")
    @GetMapping("/invite-records")
    public AjaxResult inviteRecords() { return AjaxResult.success(mallService.adminInviteRecords()); }

    @PreAuthorize("@ss.hasPermi('mall:invite-scene:list')")
    @GetMapping("/invite-scenes")
    public AjaxResult inviteScenes() { return AjaxResult.success(mallService.adminInviteScenes()); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')")
    @GetMapping("/invite-gift-rules")
    public AjaxResult inviteGiftRules() { return AjaxResult.success(mallService.adminInviteGiftRules()); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')")
    @GetMapping("/invite-rule-config")
    public AjaxResult inviteRuleConfig() { return AjaxResult.success(mallService.adminInviteRuleConfig()); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')")
    @PutMapping("/invite-rule-config/{id}")
    public AjaxResult updateInviteRuleConfig(@PathVariable Long id,@RequestBody Map<String,Object> body)
    { mallService.adminUpdateInviteRuleConfig(id,body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')")
    @PostMapping("/invite-gift-rules")
    public AjaxResult createInviteGiftRule(@RequestBody Map<String,Object> body)
    { return AjaxResult.success(mallService.adminCreateInviteGiftRule(body)); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')")
    @PutMapping("/invite-gift-rules/{id}")
    public AjaxResult updateInviteGiftRule(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateInviteGiftRule(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')")
    @GetMapping("/invite-gift-claims")
    public AjaxResult inviteGiftClaims() { return AjaxResult.success(mallService.adminInviteGiftClaims()); }

    @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')")
    @PutMapping("/invite-gift-claims/{id}")
    public AjaxResult updateInviteGiftClaim(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateInviteGiftClaim(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:withdrawal:list')")
    @GetMapping("/withdrawals")
    public AjaxResult withdrawals() { return AjaxResult.success(mallService.adminWithdrawals()); }

    @PreAuthorize("@ss.hasPermi('mall:withdrawal:edit')")
    @PutMapping("/withdrawals/{id}")
    public AjaxResult updateWithdrawal(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateWithdrawal(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:ticket:list')")
    @GetMapping("/tickets")
    public AjaxResult tickets() { return AjaxResult.success(mallService.adminServiceTickets()); }

    @PreAuthorize("@ss.hasPermi('mall:ticket:edit')")
    @PutMapping("/tickets/{id}")
    public AjaxResult updateTicket(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateServiceTicket(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:ticket:list')")
    @GetMapping("/tickets/{id}")
    public AjaxResult ticketDetail(@PathVariable Long id)
    {
        return AjaxResult.success(mallService.adminServiceTicketDetail(id));
    }

    @PreAuthorize("@ss.hasPermi('mall:exchange:list')")
    @GetMapping("/exchanges")
    public AjaxResult exchanges() { return AjaxResult.success(mallService.adminExchanges()); }

    @PreAuthorize("@ss.hasPermi('mall:exchange:edit')")
    @PutMapping("/exchanges/{id}")
    public AjaxResult updateExchange(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        mallService.adminUpdateExchange(id, body); return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('mall:review:list')")
    @GetMapping("/reviews")
    public AjaxResult reviews() { return AjaxResult.success(mallService.adminReviews()); }
}
