package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * Shared commerce service used by both the public mall and the administration console.
 * Keeping every write here guarantees that all three clients see the same database state.
 */
@Service
public class MallService
{
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern PAYMENT_REQUEST_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,80}$");
    private static final String PRODUCT_COLUMNS =
            "p.id, p.commission_amount AS commissionAmount, p.store_id AS storeId, p.category_id AS categoryId, p.category, p.is_trial_gift AS isTrialGift, p.name, p.short_name AS shortName, p.spec, "
          + "COALESCE((SELECT s.price FROM mall_product_sku s WHERE s.product_id=p.id AND s.status='0' ORDER BY s.is_default DESC,s.id LIMIT 1),p.price) AS price, "
          + "FLOOR(COALESCE((SELECT s.price FROM mall_product_sku s WHERE s.product_id=p.id AND s.status='0' ORDER BY s.is_default DESC,s.id LIMIT 1),p.price)*(SELECT percent FROM mall_consumption_points_config WHERE id=1)/100) AS reward, "
          + "(SELECT percent FROM mall_consumption_points_config WHERE id=1) AS pointsPercent, "
          + "COALESCE((SELECT SUM(s.stock) FROM mall_product_sku s WHERE s.product_id=p.id AND s.status='0'),p.stock) AS stock, "
          + "COALESCE((SELECT SUM(s.sales) FROM mall_product_sku s WHERE s.product_id=p.id),p.sales) AS sales, "
          + "p.image_key AS imageKey, p.gallery_images AS galleryImages, "
          + "p.description, p.origin, p.grade_name AS gradeName, p.raw_material AS rawMaterial, "
          + "p.brew_guide AS brewGuide, p.batch_no AS batchNo, p.traceability_info AS traceabilityInfo, "
          + "p.shelf_life AS shelfLife, p.status, "
          + "(SELECT COUNT(*) FROM mall_review r WHERE r.product_id=p.id AND r.status='0') AS reviewCount, "
          + "(SELECT ROUND(AVG(r.rating),1) FROM mall_review r WHERE r.product_id=p.id AND r.status='0') AS rating";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MallTestPaymentProperties testPaymentProperties;

    @Autowired
    private MallWechatService wechatService;
    @Autowired private MallWechatBindingService wechatBindingService;

    @Autowired
    private MallTeaFriendService teaFriendService;
    @Autowired private MallAccountCouponService accountCouponService;

    public Map<String, Object> bootstrap(Long customerId, boolean authenticated)
    {
        if (authenticated) ensureCustomer(customerId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", authenticated);
        result.put("customer", authenticated
                ? one("SELECT id, nickname, phone, avatar_url AS avatarUrl, points, status, "
                    + "CASE WHEN password_hash<>'' THEN 1 ELSE 0 END AS hasPassword FROM mall_customer WHERE id = ?", customerId)
                : null);
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT " + PRODUCT_COLUMNS + " FROM mall_product p WHERE p.status = '0' ORDER BY p.id");
        attachProductCatalog(products);
        result.put("products", products);
        result.put("categories", jdbc.queryForList(
                "SELECT c.id,c.parent_id AS parentId,c.category_code AS categoryCode,c.category_group AS categoryGroup,c.name,c.icon_url AS iconUrl,c.sort_no AS sortNo,c.status,"
              + "(SELECT COUNT(*) FROM mall_product p WHERE p.status='0' AND (p.category=c.name OR (c.parent_id IS NULL AND EXISTS(SELECT 1 FROM mall_category cc WHERE cc.parent_id=c.id AND cc.name=p.category)))) AS productCount "
              + "FROM mall_category c WHERE c.status<>'1' ORDER BY c.sort_no,c.id"));
        List<Map<String, Object>> cart = jdbc.queryForList(
                "SELECT " + PRODUCT_COLUMNS + ",c.id AS cartLineId,c.sku_id AS skuId,s.sku_code AS skuCode,"
              + "s.spec_name AS skuSpec,s.price AS skuPrice,s.stock AS skuStock,c.qty,c.checked FROM mall_cart c "
              + "JOIN mall_product p ON p.id=c.product_id JOIN mall_product_sku s ON s.id=c.sku_id "
              + "WHERE c.customer_id=? AND p.status='0' AND s.status='0' ORDER BY c.id", customerId);
        attachProductMedia(cart);
        result.put("cart", cart);
        result.put("orders", authenticated ? listOrders(customerId) : new ArrayList<Map<String, Object>>());
        result.put("addresses", authenticated ? listAddresses(customerId) : new ArrayList<Map<String, Object>>());
        List<Map<String, Object>> favorites = authenticated ? jdbc.queryForList(
                "SELECT " + PRODUCT_COLUMNS + " FROM mall_favorite f "
              + "JOIN mall_product p ON p.id = f.product_id WHERE f.customer_id = ? ORDER BY f.id DESC", customerId)
                : new ArrayList<Map<String, Object>>();
        attachProductCatalog(favorites);
        result.put("favorites", favorites);
        result.put("storeFavorites", authenticated ? jdbc.queryForList(
                "SELECT s.id,s.name,s.logo_url AS logoUrl,s.hero_image_url AS heroImageUrl,s.rating,"
              + "s.story,s.shipping_promise AS shippingPromise,s.service_promise AS servicePromise,f.create_time AS favoriteTime "
              + "FROM mall_store_favorite f JOIN mall_store s ON s.id=f.store_id "
              + "WHERE f.customer_id=? AND s.status='0' ORDER BY f.id DESC", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("topicFavorites", authenticated ? jdbc.queryForList(
                "SELECT t.id,t.slug,t.title,t.kicker,t.subtitle,t.hero_image_url AS heroImageUrl,"
              + "t.story_image_url AS storyImageUrl,t.create_time AS createTime "
              + "FROM mall_topic_favorite f JOIN mall_topic t ON t.id=f.topic_id "
              + "WHERE f.customer_id=? AND t.status='0' ORDER BY f.id DESC", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("notifications", authenticated ? listNotifications(customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("topics", publicTopics());
        result.put("store", publicStore(1L, customerId));
        result.put("rewards", jdbc.queryForList(
                "SELECT id,name,category,points,stock,stock_unit AS stockUnit,limit_qty AS limitQty,image_key AS imageKey,"
              + "exchange_notes AS exchangeNotes,delivery_method AS deliveryMethod,status "
              + "FROM mall_reward WHERE status = '0' AND points>0 ORDER BY id"));
        result.put("pointLogs", authenticated ? jdbc.queryForList(
                "SELECT id,DATE_FORMAT(create_time,'%Y-%m-%d') AS date,create_time AS createTime,title,description AS `desc`,"
              + "business_type AS businessType,business_key AS businessKey,related_business_no AS relatedBusinessNo,"
              + "amount,balance_before AS balanceBefore,balance_after AS balanceAfter,balance "
              + "FROM mall_points_log WHERE customer_id = ? ORDER BY create_time DESC, id DESC LIMIT 50", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("aftersales", authenticated ? jdbc.queryForList(
                "SELECT a.id, a.aftersale_no AS aftersaleNo, o.order_no AS orderNo, a.order_item_id AS orderItemId, a.type_name AS typeName, "
              + "a.reason, a.status, a.requested_amount AS requestedAmount, a.refund_amount AS refundAmount, "
              + "a.admin_remark AS adminRemark, a.refund_no AS refundNo, a.create_time AS createTime, a.refund_time AS refundTime "
              + "FROM mall_aftersale a JOIN mall_order o ON o.id = a.order_id WHERE a.customer_id = ? ORDER BY a.id DESC", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("serviceTickets", authenticated ? jdbc.queryForList(
                "SELECT id,ticket_no AS ticketNo,category,content,contact,status,reply,create_time AS createTime "
              + "FROM mall_service_ticket WHERE customer_id=? ORDER BY id DESC LIMIT 10", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("reviews", jdbc.queryForList(
                "SELECT r.id,r.product_id AS productId,r.rating,r.content,r.create_time AS createTime,"
              + "c.nickname,c.avatar_url AS avatarUrl FROM mall_review r JOIN mall_customer c ON c.id=r.customer_id "
              + "WHERE r.status='0' ORDER BY r.id DESC"));
        result.put("exchanges", authenticated ? jdbc.queryForList(
                "SELECT e.id,e.exchange_no AS exchangeNo,e.reward_id AS rewardId,r.name,r.image_key AS imageKey,e.qty,e.points_cost AS pointsCost,"
              + "e.status,e.receiver_name AS receiverName,e.receiver_phone AS receiverPhone,e.receiver_address AS receiverAddress,"
              + "e.carrier,e.tracking_no AS trackingNo,e.received_time AS receivedTime,e.create_time AS createTime,e.update_time AS updateTime FROM mall_exchange e "
              + "JOIN mall_reward r ON r.id=e.reward_id WHERE e.customer_id=? AND e.score_currency='POINTS' ORDER BY e.id DESC", customerId)
                : new ArrayList<Map<String, Object>>());
        result.put("distribution", authenticated ? distribution(customerId) : null);
        result.put("exchangeMonthUsed", authenticated ? jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty),0) FROM mall_exchange WHERE customer_id=? "
              + "AND score_currency='POINTS' AND create_time>=DATE_FORMAT(CURDATE(),'%Y-%m-01') AND status<>'已取消'", Integer.class, customerId) : 0);
        return result;
    }

    public List<Map<String, Object>> listOrders(Long customerId)
    {
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT o.id, o.order_no AS no, o.status, o.total_amount AS totalAmount, "
              + "o.shipping_fee AS shippingFee, o.discount_amount AS discountAmount, "
              + "o.points_discount AS pointsDiscount, o.points_used AS pointsUsed, o.paid_amount AS paidAmount, "
              + "o.payment_method AS paymentMethod, o.payment_status AS paymentStatus, "
              + "o.paid_time AS paidTime, o.is_test_order AS isTestOrder, "
              + "o.reward_points AS rewardPoints, o.receiver_name AS receiverName, "
              + "o.receiver_phone AS receiverPhone, o.receiver_address AS receiverAddress, "
              + "o.carrier, o.tracking_no AS trackingNo, o.create_time AS createTime, "
              + "(SELECT i.product_name FROM mall_order_item i WHERE i.order_id=o.id ORDER BY i.id LIMIT 1) AS name, "
              + "(SELECT i.spec FROM mall_order_item i WHERE i.order_id=o.id ORDER BY i.id LIMIT 1) AS spec, "
              + "(SELECT i.price FROM mall_order_item i WHERE i.order_id=o.id ORDER BY i.id LIMIT 1) AS price, "
              + "(SELECT i.image_key FROM mall_order_item i WHERE i.order_id=o.id ORDER BY i.id LIMIT 1) AS imageKey, "
              + "(SELECT SUM(i.qty) FROM mall_order_item i WHERE i.order_id=o.id) AS totalQty "
              + "FROM mall_order o WHERE o.customer_id = ? AND o.customer_deleted=0 "
              + "ORDER BY o.create_time DESC, o.id DESC", customerId);
        for (Map<String, Object> order : orders)
        {
            order.put("items", jdbc.queryForList(
                    "SELECT product_id AS productId,sku_id AS skuId,sku_code AS skuCode,product_name AS name,spec,price,qty,image_key AS imageKey," +
                    "price*qty AS subtotal,(SELECT r.id FROM mall_review r WHERE r.order_item_id=mall_order_item.id LIMIT 1) AS reviewId "
                    + "FROM mall_order_item WHERE order_id=? ORDER BY id", order.get("id")));
        }
        return orders;
    }

    /** Exact, ownership-checked order detail used by deep links and the cashier. */
    public Map<String, Object> orderDetail(Long customerId, String orderNo)
    {
        Map<String, Object> order = first("SELECT id FROM mall_order WHERE customer_id=? AND order_no=? "
                + "AND customer_deleted=0", customerId, orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在或无权访问");
        return publicOrderResult(longValue(order.get("id"), null), false);
    }

    public boolean testPaymentAvailable(Long customerId)
	{
		return testPaymentAvailable(customerId, null);
	}

	public boolean testPaymentAvailable(Long customerId, String buildChannel)
    {
        if (customerId == null) return false;
        Map<String, Object> customer = first("SELECT phone,status FROM mall_customer WHERE id=?", customerId);
        return customer != null && !"2".equals(stringValue(customer.get("status")))
				&& testPaymentProperties.isAvailableFor(customerId,
						stringValue(customer.get("phone")), buildChannel);
    }

    public List<Map<String, Object>> listAddresses(Long customerId)
    {
        return jdbc.queryForList(
                "SELECT id, receiver_name AS name, receiver_phone AS phone, region AS line1, "
              + "detail_address AS line2, is_default AS isDefault FROM mall_address "
              + "WHERE customer_id = ? ORDER BY is_default DESC, id", customerId);
    }

    public List<Map<String, Object>> logistics(Long customerId, String orderNo)
    {
        return jdbc.queryForList(
                "SELECT l.status_name AS title, DATE_FORMAT(l.event_time, '%m-%d %H:%i') AS time, "
              + "l.description AS `desc` FROM mall_logistics l JOIN mall_order o ON o.id=l.order_id "
              + "WHERE o.customer_id=? AND o.order_no=? ORDER BY l.sort_no", customerId, orderNo);
    }

    public List<Map<String, Object>> publicCategories()
    {
        return jdbc.queryForList("SELECT c.id,c.parent_id AS parentId,c.category_code AS categoryCode,c.category_group AS categoryGroup,c.name,c.icon_url AS iconUrl,c.sort_no AS sortNo,c.status,"
                + "(SELECT COUNT(*) FROM mall_product p WHERE p.status='0' AND (p.category=c.name OR (c.parent_id IS NULL AND EXISTS(SELECT 1 FROM mall_category cc WHERE cc.parent_id=c.id AND cc.name=p.category))) "
                + "AND EXISTS(SELECT 1 FROM mall_product_sku s WHERE s.product_id=p.id AND s.status='0')) AS productCount "
                + "FROM mall_category c WHERE c.status<>'1' ORDER BY c.sort_no,c.id");
    }

    public Map<String, Object> searchProducts(String keyword, String category, String sort,
            BigDecimal minPrice, BigDecimal maxPrice, int page, int pageSize)
    {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedCategory = category == null ? "" : category.trim();
        if (normalizedKeyword.length() > 64) throw new IllegalArgumentException("搜索关键词不能超过64个字");
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(50, pageSize));
        StringBuilder where = new StringBuilder(" WHERE p.status='0' AND EXISTS(SELECT 1 FROM mall_product_sku sx WHERE sx.product_id=p.id AND sx.status='0')");
        List<Object> parameters = new ArrayList<Object>();
        if (!normalizedKeyword.isEmpty())
        {
            where.append(" AND (p.name LIKE ? OR p.short_name LIKE ? OR p.category LIKE ? OR p.origin LIKE ?)");
            String like = "%" + normalizedKeyword + "%";
            parameters.add(like); parameters.add(like); parameters.add(like); parameters.add(like);
        }
        if (!normalizedCategory.isEmpty() && !"全部".equals(normalizedCategory))
        {
            where.append(" AND p.category=?");
            parameters.add(normalizedCategory);
        }
        if (minPrice != null)
        {
            where.append(" AND EXISTS(SELECT 1 FROM mall_product_sku smn WHERE smn.product_id=p.id AND smn.status='0' AND smn.price>=?)");
            parameters.add(minPrice);
        }
        if (maxPrice != null)
        {
            where.append(" AND EXISTS(SELECT 1 FROM mall_product_sku smx WHERE smx.product_id=p.id AND smx.status='0' AND smx.price<=?)");
            parameters.add(maxPrice);
        }
        String orderBy;
        if ("sales".equalsIgnoreCase(sort)) orderBy = " ORDER BY sales DESC,p.id DESC";
        else if ("priceAsc".equalsIgnoreCase(sort)) orderBy = " ORDER BY price ASC,p.id DESC";
        else if ("priceDesc".equalsIgnoreCase(sort)) orderBy = " ORDER BY price DESC,p.id DESC";
        else if ("newest".equalsIgnoreCase(sort)) orderBy = " ORDER BY p.create_time DESC,p.id DESC";
        else orderBy = " ORDER BY p.id";

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM mall_product p" + where,
                parameters.toArray(), Long.class);
        List<Object> queryParameters = new ArrayList<Object>(parameters);
        queryParameters.add(safeSize);
        queryParameters.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT " + PRODUCT_COLUMNS
                + " FROM mall_product p" + where + orderBy + " LIMIT ? OFFSET ?", queryParameters.toArray());
        attachProductCatalog(items);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("hasMore", total != null && ((long) safePage * safeSize) < total.longValue());
        return result;
    }

    public Map<String, Object> publicProduct(Long productId, Long customerId)
    {
        Map<String, Object> product = requireProduct(productId);
        List<Map<String, Object>> products = new ArrayList<Map<String, Object>>();
        products.add(product);
        attachProductCatalog(products);
        Long storeId = longValue(product.get("storeId"), 1L);
        product.put("store", publicStoreSummary(storeId, customerId));
        product.put("reviews", jdbc.queryForList("SELECT r.id,r.rating,r.content,r.create_time AS createTime,"
                + "c.nickname,c.avatar_url AS avatarUrl FROM mall_review r JOIN mall_customer c ON c.id=r.customer_id "
                + "WHERE r.product_id=? AND r.status='0' ORDER BY r.id DESC LIMIT 50", productId));
        product.put("favorite", customerId != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_favorite WHERE customer_id=? AND product_id=?",
                Integer.class, customerId, productId) > 0);
        return product;
    }

    public List<Map<String, Object>> publicTopics()
    {
        return jdbc.queryForList("SELECT id,slug,title,kicker,subtitle,hero_image_url AS heroImageUrl,"
                + "story_image_url AS storyImageUrl,story_title AS storyTitle,story_content AS storyContent,"
                + "start_time AS startTime,end_time AS endTime,sort_no AS sortNo FROM mall_topic "
                + "WHERE status='0' AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW()) "
                + "ORDER BY sort_no,id");
    }

    public Map<String, Object> publicTopic(String slug)
    {
        Map<String, Object> topic = first("SELECT id,slug,title,kicker,subtitle,hero_image_url AS heroImageUrl,"
                + "story_image_url AS storyImageUrl,story_title AS storyTitle,story_content AS storyContent,"
                + "start_time AS startTime,end_time AS endTime,sort_no AS sortNo FROM mall_topic "
                + "WHERE slug=? AND status='0' AND (start_time IS NULL OR start_time<=NOW()) "
                + "AND (end_time IS NULL OR end_time>=NOW())", slug);
        if (topic == null) throw new IllegalArgumentException("专题不存在或已结束");
        List<Map<String, Object>> products = jdbc.queryForList("SELECT " + PRODUCT_COLUMNS
                + " FROM mall_topic_product tp JOIN mall_product p ON p.id=tp.product_id "
                + "WHERE tp.topic_id=? AND p.status='0' ORDER BY tp.sort_no,p.id", topic.get("id"));
        attachProductCatalog(products);
        topic.put("products", products);
        return topic;
    }

    public Map<String, Object> publicStore(Long storeId, Long customerId)
    {
        Map<String, Object> store = publicStoreSummary(storeId, customerId);
        List<Map<String, Object>> products = jdbc.queryForList("SELECT " + PRODUCT_COLUMNS
                + " FROM mall_product p WHERE p.store_id=? AND p.status='0' ORDER BY p.id", storeId);
        attachProductCatalog(products);
        store.put("products", products);
        return store;
    }

    @Transactional
    public void followStore(Long customerId, Long storeId, boolean followed)
    {
        ensureCustomer(customerId);
        one("SELECT id FROM mall_store WHERE id=? AND status='0'", storeId);
        if (followed)
            jdbc.update("INSERT IGNORE INTO mall_store_favorite(store_id,customer_id) VALUES(?,?)", storeId, customerId);
        else
            jdbc.update("DELETE FROM mall_store_favorite WHERE store_id=? AND customer_id=?", storeId, customerId);
    }

    @Transactional
    public void updateCart(Long customerId, Long productId, Long skuId, int qty, boolean checked)
    {
        ensureCustomer(customerId);
        Map<String, Object> sku = requireSku(productId, skuId);
        Long selectedSkuId = longValue(sku.get("skuId"), null);
        if (qty <= 0)
        {
            jdbc.update("DELETE FROM mall_cart WHERE customer_id=? AND sku_id=?", customerId, selectedSkuId);
            return;
        }
        int stock = intValue(sku.get("stock"), 0);
        if (qty > stock) throw new IllegalArgumentException("所选规格库存不足");
        jdbc.update("INSERT INTO mall_cart(customer_id,product_id,sku_id,qty,checked) VALUES(?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE qty=VALUES(qty), checked=VALUES(checked)",
                customerId, productId, selectedSkuId, Math.min(qty, 99), checked ? 1 : 0);
    }

    @Transactional
    public void removeCart(Long customerId, List<?> skuIds, List<?> productIds)
    {
        if (skuIds != null && !skuIds.isEmpty())
        {
            for (Object skuId : skuIds)
                jdbc.update("DELETE FROM mall_cart WHERE customer_id=? AND sku_id=?", customerId, longValue(skuId, 0L));
            return;
        }
        if (productIds == null) return;
        for (Object productId : productIds)
        {
            jdbc.update("DELETE FROM mall_cart WHERE customer_id=? AND product_id=?", customerId, longValue(productId, 0L));
        }
    }

    @Transactional
    public Map<String, Object> createOrder(Long customerId, Map<String, Object> body)
	{
		return createOrder(customerId, body, null);
	}

	@Transactional
	public Map<String, Object> createOrder(Long customerId, Map<String, Object> body, String buildChannel)
    {
        String requestNo = stringValue(body.get("requestNo")).trim();
        if (requestNo.isEmpty()) throw new IllegalArgumentException("创建订单必须提供幂等请求号");
        if (!PAYMENT_REQUEST_PATTERN.matcher(requestNo).matches())
            throw new IllegalArgumentException("创建订单请求号格式无效");
        Map<String, Object> lockedCustomer = first("SELECT id,phone,status FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        if (lockedCustomer == null || "2".equals(stringValue(lockedCustomer.get("status"))))
            throw new IllegalArgumentException("用户不存在或已被禁用");
        Map<String, Object> existingOrder = first(
                "SELECT id FROM mall_order WHERE customer_id=? AND request_no=?", customerId, requestNo);
        if (existingOrder != null)
            return publicOrderResult(longValue(existingOrder.get("id"), null), true);
		boolean testOrder = testPaymentProperties.isAvailableFor(customerId,
				stringValue(lockedCustomer.get("phone")), buildChannel);
        List<Map<String, Object>> requested = mapList(body.get("items"));
        if (requested.isEmpty())
        {
            requested = jdbc.queryForList("SELECT product_id AS productId,sku_id AS skuId,qty FROM mall_cart WHERE customer_id=? AND checked=1", customerId);
        }
        if (requested.isEmpty())
        {
            throw new IllegalArgumentException("请先选择要结算的商品");
        }

        Long addressId = longValue(body.get("addressId"), null);
        Map<String, Object> address = addressId == null
                ? first("SELECT * FROM mall_address WHERE customer_id=? ORDER BY is_default DESC, id LIMIT 1", customerId)
                : first("SELECT * FROM mall_address WHERE id=? AND customer_id=?", addressId, customerId);
        if (address == null)
        {
            throw new IllegalArgumentException("请先添加收货地址");
        }
        requireCompleteAddress(address);

        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> request : requested)
        {
            Long productId = longValue(request.get("productId"), longValue(request.get("id"), null));
            Long skuId = longValue(request.get("skuId"), null);
            int qty = Math.max(1, intValue(request.get("qty"), 1));
            Map<String, Object> product = requireProduct(productId);
            Map<String, Object> sku = requireSku(productId, skuId);
            int stock = intValue(sku.get("stock"), 0);
            if (stock < qty)
            {
                throw new IllegalArgumentException(product.get("name") + "库存不足");
            }
            BigDecimal price = decimalValue(sku.get("price"));
            total = total.add(price.multiply(new BigDecimal(qty)));
            product.put("skuId", sku.get("skuId"));
            product.put("skuCode", sku.get("skuCode"));
            product.put("spec", sku.get("spec"));
            product.put("price", price);
            product.put("qty", qty);
            items.add(product);
        }

        String orderNo = serial("O");
        BigDecimal shippingFee = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        Long couponId = longValue(body.get("couponId"), null);
        BigDecimal couponAmount = BigDecimal.ZERO;
        if (couponId != null)
        {
            couponAmount = validateCoupon(customerId, couponId, total, items);
            discountAmount = couponAmount;
        }
        BigDecimal pointsDiscount = BigDecimal.ZERO;
        int pointsUsed = 0;
        if (boolValue(body.get("usePoints"), false))
        {
            int pointsBalance = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId);
            BigDecimal pointsValue = new BigDecimal(pointsBalance).divide(new BigDecimal("100"), 2, RoundingMode.DOWN);
            BigDecimal orderCap = total.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.DOWN);
            pointsDiscount = pointsValue.min(orderCap).min(total);
            pointsUsed = pointsDiscount.multiply(new BigDecimal("100")).intValue();
        }
        BigDecimal paidAmount = total.add(shippingFee).subtract(discountAmount).subtract(pointsDiscount);
        int rewardPoints = rewardPoints(paidAmount);
        allocateOrderItemAmounts(items, total, couponAmount, pointsDiscount, shippingFee);
        for (Map<String, Object> item : items)
        {
            int qty = intValue(item.get("qty"), 1);
            int changedSkuStock = jdbc.update(
                    "UPDATE mall_product_sku SET stock=stock-? WHERE id=? AND status='0' AND stock>=?",
                    qty, item.get("skuId"), qty);
            if (changedSkuStock != 1)
                throw new IllegalArgumentException(stringValue(item.get("name")) + "所选规格库存不足或已停用");
            int changedProductStock = jdbc.update(
                    "UPDATE mall_product SET stock=stock-? WHERE id=? AND status='0' AND stock>=?",
                    qty, item.get("id"), qty);
            if (changedProductStock != 1)
                throw new IllegalArgumentException(stringValue(item.get("name")) + "库存不足或已下架");
        }
        Map<String, String> invoice = validateInvoice(body.get("invoice"));
        jdbc.update("INSERT INTO mall_order(order_no, request_no, customer_id, is_test_order, status, total_amount, shipping_fee, discount_amount, coupon_id, coupon_amount, "
                  + "points_discount, points_used, paid_amount, payment_method, payment_status, inventory_state, reserved_until, reward_points, "
                  + "receiver_name, receiver_phone, receiver_address, carrier, tracking_no, remark,invoice_type,invoice_title,invoice_tax_no,invoice_email) "
                  + "VALUES(?,?,?,?,'待付款',?,?,?,?,?,?,?,?,'未支付','待支付','RESERVED',DATE_ADD(NOW(),INTERVAL 30 MINUTE),?,?,?,?,'','',?,?,?,?,?)",
                orderNo, requestNo, customerId, testOrder ? 1 : 0, total, shippingFee, discountAmount, couponId, couponAmount, pointsDiscount, pointsUsed, paidAmount, rewardPoints,
                address.get("receiver_name"), address.get("receiver_phone"),
                String.valueOf(address.get("region")) + " " + String.valueOf(address.get("detail_address")),
                stringValue(body.get("remark")), invoice.get("type"), invoice.get("title"), invoice.get("taxNo"), invoice.get("email"));
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (couponId != null) lockCoupon(customerId, couponId, orderId, orderNo, couponAmount);

        for (Map<String, Object> item : items)
        {
            int qty = intValue(item.get("qty"), 1);
            jdbc.update("INSERT INTO mall_order_item(order_id,product_id,sku_id,sku_code,product_name,spec,price,gross_amount,coupon_allocated,points_allocated,shipping_allocated,refundable_amount,qty,image_key) "
                      + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", orderId, item.get("id"), item.get("skuId"), item.get("skuCode"),
                    item.get("name"), item.get("spec"), item.get("price"), item.get("grossAmount"), item.get("couponAllocated"),
                    item.get("pointsAllocated"), item.get("shippingAllocated"), item.get("refundableAmount"), qty, item.get("imageKey"));
            jdbc.update("UPDATE mall_order_item i JOIN mall_product p ON p.id=i.product_id SET i.is_trial_gift=p.is_trial_gift,i.commission_amount=p.commission_amount WHERE i.order_id=? AND i.product_id=?",orderId,item.get("id"));
        }
        jdbc.update("INSERT INTO mall_logistics(order_id, status_name, description, event_time, sort_no) "
                  + "VALUES(?, '订单已创建', '订单等待用户完成支付', NOW(), 1)", orderId);
        Map<String, Object> createdOrder = new LinkedHashMap<String, Object>();
        createdOrder.put("id", orderId);
        createdOrder.put("order_no", orderNo);
        logOrderTransition(createdOrder, "用户", customerId, "确认订单页", "", "待付款", "创建订单", requestNo);
        notifyCustomer(customerId, "订单", "订单已创建", "订单 " + orderNo + " 等待付款",
                "orderDetail", "orderNo=" + orderNo, "ORDER", orderNo, "CREATED");
        return publicOrderResult(orderId, false);
    }

    /** Server-side checkout quote. No cart or order data is written. */
    public Map<String, Object> checkoutQuote(Long customerId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        List<Map<String, Object>> requested = mapList(body.get("items"));
        if (requested.isEmpty()) throw new IllegalArgumentException("请选择要结算的商品");
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> request : requested)
        {
            Long productId = longValue(request.get("productId"), null);
            Long skuId = longValue(request.get("skuId"), null);
            int qty = intValue(request.get("qty"), 1);
            if (qty < 1 || qty > 99) throw new IllegalArgumentException("购买数量必须为1至99件");
            Map<String, Object> product = requireProduct(productId);
            Map<String, Object> sku = requireSku(productId, skuId);
            if (intValue(sku.get("stock"), 0) < qty)
                throw new IllegalArgumentException(product.get("name") + "库存不足");
            BigDecimal price = decimalValue(sku.get("price"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("productId", productId);
            item.put("skuId", sku.get("skuId"));
            item.put("skuCode", sku.get("skuCode"));
            item.put("name", product.get("name"));
            item.put("spec", sku.get("spec"));
            item.put("price", price);
            item.put("qty", qty);
            item.put("stock", sku.get("stock"));
            item.put("imageKey", product.get("imageKey"));
            total = total.add(price.multiply(BigDecimal.valueOf(qty)));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("totalAmount", total.setScale(2, RoundingMode.HALF_UP));
        result.put("shippingFee", BigDecimal.ZERO.setScale(2));
        result.put("paidAmount", total.setScale(2, RoundingMode.HALF_UP));
        result.put("quotedAt", new Date());
        return result;
    }

    /**
     * Explicit non-production test payment. The order row lock and unique request
     * number make repeated clicks and concurrent requests idempotent.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> testPay(Long customerId, String orderNo, String requestNo)
	{
		return testPay(customerId, orderNo, requestNo, null);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> testPay(Long customerId, String orderNo, String requestNo, String buildChannel)
    {
        Map<String, Object> customer = first("SELECT phone,status FROM mall_customer WHERE id=?", customerId);
        if (customer == null || "2".equals(stringValue(customer.get("status"))))
            throw new MallAuthorizationException("用户不存在或已被禁用");
		testPaymentProperties.requireAvailableFor(customerId,
				stringValue(customer.get("phone")), buildChannel);
        String normalizedRequestNo = requestNo == null ? "" : requestNo.trim();
        if (!PAYMENT_REQUEST_PATTERN.matcher(normalizedRequestNo).matches())
        {
            throw new IllegalArgumentException("测试支付请求号格式无效");
        }

        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE",
                customerId, orderNo);
        if (order == null)
        {
            throw new IllegalArgumentException("订单不存在");
        }
        Long orderId = ((Number) order.get("id")).longValue();
        if (!boolValue(order.get("is_test_order"), false))
        {
            // A real WeChat account can be added to the server-side test
            // whitelist after it created a still-unpaid order. Promote only
            // that owned, payable order; no client flag can trigger this path.
            int promoted = jdbc.update("UPDATE mall_order SET is_test_order=1 WHERE id=? "
                    + "AND customer_id=? AND status='待付款' AND payment_status='待支付'",
                    orderId, customerId);
            if (promoted != 1)
                throw new MallAuthorizationException("该订单不能使用测试支付");
            order.put("is_test_order", 1);
        }
        Map<String, Object> existing = first(
                "SELECT order_id,customer_id,status FROM mall_payment_attempt WHERE request_no=?", normalizedRequestNo);
        if (existing != null)
        {
            if (longValue(existing.get("order_id"), -1L).longValue() != orderId.longValue()
                    || longValue(existing.get("customer_id"), -1L).longValue() != customerId.longValue())
            {
                throw new IllegalArgumentException("测试支付请求号已被其他订单使用");
            }
            if ("成功".equals(stringValue(existing.get("status"))))
            {
                return publicOrderResult(orderId, true);
            }
            throw new IllegalArgumentException("测试支付正在处理，请勿重复提交");
        }

        if (MallOrderStateMachine.PAYMENT_PAID.equals(stringValue(order.get("payment_status"))))
        {
            return publicOrderResult(orderId, true);
        }
        MallOrderStateMachine.requirePayable(stringValue(order.get("status")),
                stringValue(order.get("payment_status")));

        jdbc.update("INSERT INTO mall_payment_attempt(request_no,order_id,customer_id,payment_type,status,amount,environment) "
                  + "VALUES(?,?,?,'TEST','处理中',?,?)", normalizedRequestNo, orderId, customerId,
                order.get("paid_amount"), testPaymentProperties.getEnvironment());

        int pointsUsed = intValue(order.get("points_used"), 0);
        if (pointsUsed > 0)
        {
            int pointsBalance = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=? FOR UPDATE",
                    Integer.class, customerId);
            if (pointsBalance < pointsUsed)
            {
                throw new IllegalArgumentException("积分余额已变化，请取消订单后重新结算");
            }
            boolean deducted = addPointsUnique(customerId, -pointsUsed, "订单积分抵扣", "订单号：" + orderNo,
                    "ORDER_POINTS_USE", orderNo, orderId);
            if (!deducted)
            {
                throw new IllegalArgumentException("订单积分已处理，请刷新订单状态");
            }
        }

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT product_id,sku_id,product_name,qty FROM mall_order_item WHERE order_id=? ORDER BY id", orderId);
        if (items.isEmpty())
        {
            throw new IllegalArgumentException("订单商品明细为空，不能支付");
        }
        boolean alreadyReserved = "RESERVED".equals(stringValue(order.get("inventory_state")));
        for (Map<String, Object> item : items)
        {
            int qty = intValue(item.get("qty"), 0);
            Long skuId = longValue(item.get("sku_id"), null);
            if (skuId == null)
            {
                Map<String, Object> defaultSku = requireSku(longValue(item.get("product_id"), null), null);
                skuId = longValue(defaultSku.get("skuId"), null);
            }
            if (!alreadyReserved)
            {
                int changedSkuStock = jdbc.update(
                        "UPDATE mall_product_sku SET stock=stock-? WHERE id=? AND status='0' AND stock>=?",
                        qty, skuId, qty);
                if (changedSkuStock == 0)
                    throw new IllegalArgumentException(stringValue(item.get("product_name")) + "所选规格库存不足或已停用");
                int changedStock = jdbc.update(
                        "UPDATE mall_product SET stock=stock-? WHERE id=? AND status='0' AND stock>=?",
                        qty, item.get("product_id"), qty);
                if (changedStock == 0)
                    throw new IllegalArgumentException(stringValue(item.get("product_name")) + "库存不足或已下架");
            }
            jdbc.update("DELETE FROM mall_cart WHERE customer_id=? AND sku_id=?", customerId, skuId);
        }

        int paid = jdbc.update("UPDATE mall_order SET status='待发货',payment_method='测试支付',"
                + "payment_status='已支付',inventory_state='CONSUMED',reserved_until=NULL,paid_time=NOW() "
                + "WHERE id=? AND status='待付款' AND payment_status='待支付'", orderId);
        if (paid != 1)
        {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        useCoupon(customerId, orderId, orderNo);
        // Test orders include commission and withdrawal; retain test markers for launch cleanup.
        createPendingCommissions(orderId, customerId, decimalValue(order.get("paid_amount")));
        jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                  + "SELECT ?,'测试支付成功','隔离环境测试支付已完成，等待商家发货',NOW(),2 WHERE NOT EXISTS "
                  + "(SELECT 1 FROM mall_logistics WHERE order_id=? AND status_name='测试支付成功')", orderId, orderId);
        jdbc.update("UPDATE mall_payment_attempt SET status='成功',processed_at=NOW() WHERE request_no=?",
                normalizedRequestNo);
        logOrderTransition(order, "用户", customerId, "测试支付页", "待付款", "待发货", "测试支付成功", normalizedRequestNo);
        recordPendingOrderPoints(order);
        teaFriendService.reconcileBuyer(customerId);
        notifyCustomer(customerId, "订单", "测试支付成功", "订单 " + orderNo + " 已完成测试支付，等待商家发货",
                "orderDetail", "orderNo=" + orderNo, "ORDER", orderNo, "PAID");
        return publicOrderResult(orderId, false);
    }

    @Transactional
    public Map<String, Object> testPayFailure(Long customerId, String orderNo, String requestNo, String buildChannel)
    {
        Map<String, Object> customer = first("SELECT phone,status FROM mall_customer WHERE id=?", customerId);
        if (customer == null || "2".equals(stringValue(customer.get("status")))) throw new MallAuthorizationException("用户不存在或已被禁用");
        testPaymentProperties.requireAvailableFor(customerId,
                stringValue(customer.get("phone")), buildChannel);
        if (!PAYMENT_REQUEST_PATTERN.matcher(stringValue(requestNo)).matches()) throw new IllegalArgumentException("测试支付请求号格式无效");
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE", customerId, orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        MallOrderStateMachine.requirePayable(stringValue(order.get("status")), stringValue(order.get("payment_status")));
        Map<String, Object> existing = first("SELECT order_id,customer_id,status FROM mall_payment_attempt WHERE request_no=?", requestNo);
        if (existing != null)
        {
            if (!longValue(existing.get("order_id"), -1L).equals(longValue(order.get("id"), -2L))
                    || !longValue(existing.get("customer_id"), -1L).equals(customerId))
                throw new IllegalArgumentException("测试支付请求号已被其他订单使用");
            return publicOrderResult(longValue(order.get("id"), null), true);
        }
        jdbc.update("INSERT INTO mall_payment_attempt(request_no,order_id,customer_id,payment_type,status,amount,environment,processed_at) "
                + "VALUES(?,?,?,'TEST','失败',?,?,NOW())", requestNo, order.get("id"), customerId,
                order.get("paid_amount"), testPaymentProperties.getEnvironment());
        return publicOrderResult(longValue(order.get("id"), null), true);
    }

    @Transactional
    public void updatePublicOrderStatus(Long customerId, String orderNo, String status)
    {
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE", customerId, orderNo);
        if (order == null)
        {
            throw new IllegalArgumentException("订单不存在");
        }
        String oldStatus = String.valueOf(order.get("status"));
        MallOrderStateMachine.requirePublicTransition(oldStatus, status);
        jdbc.update("UPDATE mall_order SET status=? WHERE id=?", status, order.get("id"));
        if ("已完成".equals(status) && !"已完成".equals(oldStatus))
        {
            awardOrderPoints(order);
            jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                      + "VALUES(?,'已签收','订单已确认收货',NOW(),99)", order.get("id"));
            settleCommissions(((Number) order.get("id")).longValue());
        }
        if ("已取消".equals(status))
        {
            releasePendingReservation(order);
            cancelPendingOrderPoints(((Number)order.get("id")).longValue());
            restoreOrderPoints(order);
            cancelCommissions(((Number) order.get("id")).longValue());
            releaseCoupon(((Number) order.get("id")).longValue(), "订单取消");
        }
        notifyCustomer(customerId, "订单", "订单状态已更新", "订单 " + orderNo + " 已更新为“" + status + "”",
                "orderDetail", "orderNo=" + orderNo, "ORDER", orderNo, "PUBLIC_" + status);
        logOrderTransition(order, "用户", customerId, "小程序/H5", oldStatus, status, "用户确认收货", null);
    }

    @Transactional
    public Map<String, Object> cancelOrder(Long customerId, String orderNo, Map<String, Object> body)
    {
        String requestNo = required(body, "requestNo").trim();
        if (!PAYMENT_REQUEST_PATTERN.matcher(requestNo).matches()) throw new IllegalArgumentException("取消请求号格式无效");
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE", customerId, orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在或无权访问");
        String oldStatus = stringValue(order.get("status"));
        if (MallOrderStateMachine.CANCELLED.equals(oldStatus)) return publicOrderResult(longValue(order.get("id"), null), true);
        if (!MallOrderStateMachine.WAITING_PAYMENT.equals(oldStatus))
        {
            if (MallOrderStateMachine.WAITING_SHIPMENT.equals(oldStatus))
                throw new IllegalArgumentException("订单已支付，请申请仅退款");
            throw new IllegalArgumentException("订单已发货或已完成，请通过售后入口处理");
        }
        String reason = required(body, "reason").trim();
        String note = stringValue(body.get("note")).trim();
        if (reason.length() > 64 || note.length() > 500) throw new IllegalArgumentException("取消原因或说明过长");
        int changed = jdbc.update("UPDATE mall_order SET status='已取消',cancel_reason=?,cancel_note=?,cancel_time=NOW() "
                + "WHERE id=? AND status='待付款'", reason, note, order.get("id"));
        if (changed != 1) throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        releasePendingReservation(order);
        cancelPendingOrderPoints(longValue(order.get("id"), 0L));
        restoreOrderPoints(order);
        releaseCoupon(longValue(order.get("id"), 0L), "用户取消订单");
        cancelCommissions(longValue(order.get("id"), 0L));
        logOrderTransition(order, "用户", customerId, "取消订单页", oldStatus, "已取消", reason + (note.isEmpty() ? "" : "；" + note), requestNo);
        notifyCustomer(customerId, "订单", "订单已取消", "订单 " + orderNo + " 已取消：" + reason,
                "orderDetail", "orderNo=" + orderNo, "ORDER", orderNo, "CANCELLED");
        return publicOrderResult(longValue(order.get("id"), null), false);
    }

    @Transactional
    public Map<String, Object> expediteOrder(Long customerId, String orderNo, Map<String, Object> body)
    {
        String requestNo = required(body, "requestNo").trim();
        if (!PAYMENT_REQUEST_PATTERN.matcher(requestNo).matches()) throw new IllegalArgumentException("催单请求号格式无效");
        Map<String, Object> existing = first("SELECT id,request_no AS requestNo,status,create_time AS createTime "
                + "FROM mall_order_expedite WHERE customer_id=? AND request_no=?", customerId, requestNo);
        if (existing != null) return existing;
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE", customerId, orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在或无权访问");
        if (!"待发货".equals(stringValue(order.get("status")))) throw new IllegalArgumentException("只有待发货订单可以催发货");
        int recent = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order_expedite WHERE order_id=? "
                + "AND create_time>=DATE_SUB(NOW(),INTERVAL 6 HOUR)", Integer.class, order.get("id"));
        if (recent > 0) throw new IllegalArgumentException("已提醒商家，请6小时后再试");
        jdbc.update("INSERT INTO mall_order_expedite(request_no,order_id,order_no,customer_id,status,customer_remark) "
                + "VALUES(?,?,?,?, '待处理',?)", requestNo, order.get("id"), orderNo, customerId,
                stringValue(body.get("remark")));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        notifyCustomer(customerId, "订单", "催发货已提交", "订单 " + orderNo + " 已提醒商家尽快发货",
                "orderDetail", "orderNo=" + orderNo, "ORDER", orderNo, "EXPEDITE");
        return one("SELECT id,request_no AS requestNo,status,create_time AS createTime FROM mall_order_expedite WHERE id=?", id);
    }

    public void deleteOrder(Long customerId, String orderNo)
    {
        int changed = jdbc.update("UPDATE mall_order SET customer_deleted=1 WHERE customer_id=? AND order_no=? "
                + "AND status IN ('已完成','已取消') AND customer_deleted=0", customerId, orderNo);
        if (changed == 0)
        {
            throw new IllegalArgumentException("只有已完成或已取消订单可以删除，或订单已删除");
        }
    }

    public Map<String, Object> aftersaleQuote(Long customerId, Map<String, Object> body)
    {
        String orderNo = required(body, "orderNo").trim();
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=?", customerId, orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在或无权访问");
        String sourceStatus = effectiveFulfillmentStatus(order);
        if (!("待发货".equals(sourceStatus) || "待收货".equals(sourceStatus) || "已完成".equals(sourceStatus)))
            throw new IllegalArgumentException("当前订单状态不支持申请售后");
        String typeName = required(body, "typeName").trim();
        validateAftersaleType(typeName, sourceStatus);
        Long orderItemId = longValue(body.get("orderItemId"), null);
        Map<String, Object> item = first("SELECT * FROM mall_order_item WHERE id=? AND order_id=?", orderItemId, order.get("id"));
        if (item == null) throw new IllegalArgumentException("请选择该订单中的商品");
        int applyQty = intValue(body.get("qty"), 1);
        int availableQty = intValue(item.get("qty"), 0) - intValue(item.get("refunded_qty"), 0)
                - intValue(item.get("aftersale_locked_qty"), 0);
        if (applyQty < 1 || applyQty > availableQty) throw new IllegalArgumentException("申请数量超过可售后数量");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("orderNo", orderNo);
        result.put("orderItemId", orderItemId);
        result.put("qty", applyQty);
        result.put("typeName", typeName);
        result.put("availableQty", availableQty);
        result.put("requestedAmount", calculateAftersaleAmount(order, item, applyQty));
        result.put("calculatedBy", "JAVA_BACKEND");
        return result;
    }

    @Transactional
    public Map<String, Object> submitAftersale(Long customerId, Map<String, Object> body)
    {
        String orderNo = stringValue(body.get("orderNo"));
        String requestNo = required(body, "requestNo").trim();
        if (!PAYMENT_REQUEST_PATTERN.matcher(requestNo).matches()) throw new IllegalArgumentException("售后请求号格式无效");
        Map<String, Object> order = first("SELECT * FROM mall_order WHERE customer_id=? AND order_no=? FOR UPDATE", customerId, orderNo);
        if (order == null)
        {
            throw new IllegalArgumentException("订单不存在");
        }
        // Serialize submissions for one order before checking the idempotency key.
        // Without this second, locked check two simultaneous requests could both
        // pass the pre-lock query and race on the unique constraint.
        Map<String, Object> existing = first("SELECT aftersale_no AS aftersaleNo FROM mall_aftersale WHERE customer_id=? AND request_no=?", customerId, requestNo);
        if (existing != null) return aftersaleDetail(customerId, stringValue(existing.get("aftersaleNo")));
        String sourceStatus = effectiveFulfillmentStatus(order);
        if (!("待发货".equals(sourceStatus) || "待收货".equals(sourceStatus) || "已完成".equals(sourceStatus)))
        {
            throw new IllegalArgumentException("当前订单状态不支持申请售后");
        }
        Long orderItemId = longValue(body.get("orderItemId"), null);
        Map<String, Object> item = first("SELECT * FROM mall_order_item WHERE id=? AND order_id=? FOR UPDATE", orderItemId, order.get("id"));
        if (item == null) throw new IllegalArgumentException("请选择该订单中的商品");
        int applyQty = intValue(body.get("qty"), 1);
        int availableQty = intValue(item.get("qty"), 0) - intValue(item.get("refunded_qty"), 0)
                - intValue(item.get("aftersale_locked_qty"), 0);
        if (applyQty < 1 || applyQty > availableQty) throw new IllegalArgumentException("申请数量超过可售后数量");
        String typeName = required(body, "typeName").trim();
        validateAftersaleType(typeName, sourceStatus);
        BigDecimal requestedAmount = calculateAftersaleAmount(order, item, applyQty);
        String aftersaleNo = serial("A");
        String evidence = textListValue(body.get("evidenceUrls"));
        jdbc.update("INSERT INTO mall_aftersale(aftersale_no,request_no,order_id,order_item_id,product_id,sku_id,apply_qty,customer_id,type_name,reason,description,evidence_urls,status,requested_amount,source_order_status) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?, '申请中',?,?)", aftersaleNo, requestNo, order.get("id"), orderItemId,
                item.get("product_id"), item.get("sku_id"), applyQty, customerId, typeName, required(body,"reason"),
                stringValue(body.get("description")), evidence, requestedAmount, sourceStatus);
        jdbc.update("UPDATE mall_order_item SET aftersale_locked_qty=aftersale_locked_qty+? WHERE id=?", applyQty, orderItemId);
        // 履约状态和售后摘要必须分离。订单仍可继续发货/收货，且同一订单的
        // 其他商品行仍然可以按剩余数量分别申请售后。
        jdbc.update("UPDATE mall_order SET aftersale_status='申请中' WHERE id=?", order.get("id"));
        Long aftersaleId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        logAftersaleTransition(aftersaleId, aftersaleNo, "用户", customerId, "售后申请页", "", "申请中", required(body,"reason"), requestNo);
        notifyCustomer(customerId, "订单", "售后申请已提交", "订单 " + orderNo + " 的售后申请等待审核",
                "orderDetail", "orderNo=" + orderNo, "AFTERSALE", aftersaleNo, "SUBMITTED");
        return aftersaleDetail(customerId, aftersaleNo);
    }

    private void validateAftersaleType(String typeName, String sourceStatus)
    {
        if (!("仅退款".equals(typeName) || "退货退款".equals(typeName) || "换货".equals(typeName)))
            throw new IllegalArgumentException("不支持的售后类型");
        if ("仅退款".equals(typeName) && "待收货".equals(sourceStatus))
            throw new IllegalArgumentException("订单已发货，请选择退货退款或换货");
    }

    private BigDecimal calculateAftersaleAmount(Map<String, Object> order, Map<String, Object> item, int applyQty)
    {
        BigDecimal lineRefundable = decimalValue(item.get("refundable_amount"));
        BigDecimal lineRefunded = decimalValue(item.get("refunded_amount"));
        int totalQty = intValue(item.get("qty"), 0);
        int completedQty = intValue(item.get("refunded_qty"), 0);
        int remainingQty = Math.max(0, totalQty - completedQty);
        if (lineRefundable.compareTo(BigDecimal.ZERO) > 0 && totalQty > 0 && remainingQty > 0)
        {
            BigDecimal remainingAmount = lineRefundable.subtract(lineRefunded).max(BigDecimal.ZERO);
            BigDecimal requested = applyQty == remainingQty
                    ? remainingAmount
                    : lineRefundable.multiply(BigDecimal.valueOf(applyQty))
                            .divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.DOWN).min(remainingAmount);
            if (requested.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("当前商品没有可退金额");
            return requested;
        }
        BigDecimal orderGross = jdbc.queryForObject("SELECT COALESCE(SUM(price*qty),0) FROM mall_order_item WHERE order_id=?", BigDecimal.class, order.get("id"));
        BigDecimal lineGross = decimalValue(item.get("price")).multiply(BigDecimal.valueOf(applyQty));
        BigDecimal paidAmount = decimalValue(order.get("paid_amount"));
        BigDecimal remainRefundable = paidAmount.subtract(decimalValue(order.get("refunded_amount"))).max(BigDecimal.ZERO);
        BigDecimal requestedAmount = orderGross.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO
                : paidAmount.multiply(lineGross).divide(orderGross, 2, RoundingMode.HALF_UP).min(remainRefundable);
        if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("当前商品没有可退金额");
        return requestedAmount;
    }

    public Map<String, Object> aftersaleDetail(Long customerId, String aftersaleNo)
    {
        Map<String, Object> result = first("SELECT a.id,a.aftersale_no AS aftersaleNo,a.request_no AS requestNo,o.order_no AS orderNo,"
                + "a.type_name AS typeName,a.reason,a.description,a.evidence_urls AS evidenceUrls,a.apply_qty AS qty,"
                + "a.requested_amount AS requestedAmount,a.refund_amount AS refundAmount,a.status,a.admin_remark AS adminRemark,"
                + "a.return_address AS returnAddress,a.return_carrier AS returnCarrier,a.return_tracking_no AS returnTrackingNo,"
                + "a.exchange_carrier AS exchangeCarrier,a.exchange_tracking_no AS exchangeTrackingNo,a.close_reason AS closeReason,"
                + "a.create_time AS createTime,a.update_time AS updateTime,a.complete_time AS completeTime,"
                + "i.id AS orderItemId,i.product_id AS productId,i.sku_id AS skuId,i.product_name AS productName,i.spec,i.price,i.image_key AS imageKey "
                + "FROM mall_aftersale a JOIN mall_order o ON o.id=a.order_id JOIN mall_order_item i ON i.id=a.order_item_id "
                + "WHERE a.customer_id=? AND a.aftersale_no=?", customerId, aftersaleNo);
        if (result == null) throw new IllegalArgumentException("售后单不存在或无权访问");
        result.put("timeline", jdbc.queryForList("SELECT old_status AS oldStatus,new_status AS newStatus,remark,source_name AS sourceName,create_time AS createTime "
                + "FROM mall_aftersale_operation_log WHERE aftersale_id=? ORDER BY id", result.get("id")));
        return result;
    }

    @Transactional
    public Map<String, Object> cancelAftersale(Long customerId, String aftersaleNo, Map<String, Object> body)
    {
        Map<String, Object> sale = first("SELECT * FROM mall_aftersale WHERE customer_id=? AND aftersale_no=? FOR UPDATE", customerId, aftersaleNo);
        if (sale == null) throw new IllegalArgumentException("售后单不存在或无权访问");
        String oldStatus = stringValue(sale.get("status"));
        if ("用户已撤销".equals(oldStatus)) return aftersaleDetail(customerId, aftersaleNo);
        if (!("申请中".equals(oldStatus) || "等待用户退货".equals(oldStatus))) throw new IllegalArgumentException("当前售后状态不能撤销");
        jdbc.update("UPDATE mall_aftersale SET status='用户已撤销',close_reason=?,complete_time=NOW() WHERE id=?", stringValue(body.get("reason")), sale.get("id"));
        releaseAftersaleQuantity(sale, false);
        restoreOrderAfterAftersaleClosed(longValue(sale.get("order_id"), 0L));
        logAftersaleTransition(longValue(sale.get("id"), 0L), aftersaleNo, "用户", customerId, "售后详情页", oldStatus, "用户已撤销", stringValue(body.get("reason")), stringValue(body.get("requestNo")));
        return aftersaleDetail(customerId, aftersaleNo);
    }

    @Transactional
    public Map<String, Object> submitReturnLogistics(Long customerId, String aftersaleNo, Map<String, Object> body)
    {
        String requestNo = normalizedRequestNo(body.get("requestNo"), "退货物流");
        Map<String, Object> sale = first("SELECT * FROM mall_aftersale WHERE customer_id=? AND aftersale_no=? FOR UPDATE", customerId, aftersaleNo);
        if (sale == null) throw new IllegalArgumentException("售后单不存在或无权访问");
        String carrier = required(body, "carrier").trim();
        String trackingNo = required(body, "trackingNo").trim();
        if (carrier.isEmpty() || trackingNo.isEmpty()) throw new IllegalArgumentException("请填写物流公司和单号");
        if (carrier.length() > 64 || trackingNo.length() > 64) throw new IllegalArgumentException("物流信息过长");
        if (aftersaleActionProcessed(sale, requestNo, "退货运输中"))
        {
            if (!carrier.equals(stringValue(sale.get("return_carrier")))
                    || !trackingNo.equals(stringValue(sale.get("return_tracking_no"))))
                throw new IllegalArgumentException("同一请求号不能提交不同物流信息");
            return aftersaleDetail(customerId, aftersaleNo);
        }
        if (!"等待用户退货".equals(stringValue(sale.get("status")))) throw new IllegalArgumentException("当前售后状态无需填写退货物流");
        jdbc.update("UPDATE mall_aftersale SET status='退货运输中',action_deadline=NULL,return_carrier=?,return_tracking_no=?,return_time=NOW() WHERE id=?", carrier, trackingNo, sale.get("id"));
        logAftersaleTransition(longValue(sale.get("id"), 0L), aftersaleNo, "用户", customerId, "退货物流页", "等待用户退货", "退货运输中", carrier + " " + trackingNo, requestNo);
        return aftersaleDetail(customerId, aftersaleNo);
    }

    // Called only after locking and checking ownership of the aftersale row.
    private boolean aftersaleActionProcessed(Map<String, Object> sale, String requestNo, String targetStatus)
    {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mall_aftersale_operation_log WHERE aftersale_id=? AND request_no=? AND new_status=?",
                Integer.class, sale.get("id"), requestNo, targetStatus);
        return count != null && count > 0;
    }

    @Transactional
    public Map<String, Object> confirmExchangeReceipt(Long customerId, String aftersaleNo, Map<String, Object> body)
    {
        String requestNo = normalizedRequestNo(body.get("requestNo"), "换货收货");
        Map<String, Object> sale = first("SELECT * FROM mall_aftersale WHERE customer_id=? AND aftersale_no=? FOR UPDATE", customerId, aftersaleNo);
        if (sale == null) throw new IllegalArgumentException("售后单不存在或无权访问");
        if (!"换货".equals(stringValue(sale.get("type_name")))) throw new IllegalArgumentException("该售后不是换货申请");
        if (aftersaleActionProcessed(sale, requestNo, "售后完成")) return aftersaleDetail(customerId, aftersaleNo);
        if (!"换货已发出".equals(stringValue(sale.get("status")))) throw new IllegalArgumentException("换货商品尚未发出或已确认");
        jdbc.update("UPDATE mall_aftersale SET status='售后完成',complete_time=NOW() WHERE id=?", sale.get("id"));
        releaseAftersaleQuantity(sale, false);
        restoreOrderAfterAftersaleClosed(longValue(sale.get("order_id"), 0L));
        logAftersaleTransition(longValue(sale.get("id"), 0L), aftersaleNo, "用户", customerId, "售后详情页", "换货已发出", "售后完成", "用户确认收到换货商品", requestNo);
        return aftersaleDetail(customerId, aftersaleNo);
    }

    @Transactional
    public Long saveAddress(Long customerId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        Long id = longValue(body.get("id"), null);
        String receiverName = required(body, "name").trim();
        String receiverPhone = required(body, "phone").trim();
        String region = required(body, "line1").trim();
        String detailAddress = required(body, "line2").trim();
        if (!receiverPhone.matches("^1\\d{10}$")) throw new IllegalArgumentException("请输入正确的11位手机号");
        if (receiverName.length() > 64 || region.length() > 255 || detailAddress.length() > 255)
            throw new IllegalArgumentException("收货地址信息过长");
        boolean isDefault = boolValue(body.get("isDefault"), false);
        if (isDefault)
        {
            jdbc.update("UPDATE mall_address SET is_default=0 WHERE customer_id=?", customerId);
        }
        if (id == null)
        {
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM mall_address WHERE customer_id=?", Integer.class, customerId);
            jdbc.update("INSERT INTO mall_address(customer_id,receiver_name,receiver_phone,region,detail_address,is_default) "
                      + "VALUES(?,?,?,?,?,?)", customerId, receiverName, receiverPhone,
                    region, detailAddress, (isDefault || count == 0) ? 1 : 0);
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        jdbc.update("UPDATE mall_address SET receiver_name=?, receiver_phone=?, region=?, detail_address=?, is_default=? "
                  + "WHERE id=? AND customer_id=?", receiverName, receiverPhone,
                region, detailAddress, isDefault ? 1 : 0, id, customerId);
        return id;
    }

    public void deleteAddress(Long customerId, Long addressId)
    {
        jdbc.update("DELETE FROM mall_address WHERE id=? AND customer_id=?", addressId, customerId);
    }

    @Transactional
    public Map<String, Object> submitServiceTicket(Long customerId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        String category = required(body, "category").trim();
        String content = required(body, "content").trim();
        String contact = stringValue(body.get("contact")).trim();
        if (category.length() > 32) throw new IllegalArgumentException("问题类型过长");
        if (content.length() < 2) throw new IllegalArgumentException("请至少填写2个字的问题描述");
        if (content.length() > 500) throw new IllegalArgumentException("问题描述不能超过500字");
        if (contact.length() > 64) throw new IllegalArgumentException("联系方式过长");
        String ticketNo = serial("S");
        jdbc.update("INSERT INTO mall_service_ticket(ticket_no,customer_id,category,content,contact,status) "
                  + "VALUES(?,?,?,?,?,'待处理')", ticketNo, customerId, category, content, contact);
        Long ticketId = jdbc.queryForObject("SELECT id FROM mall_service_ticket WHERE ticket_no=?", Long.class, ticketNo);
        jdbc.update("INSERT INTO mall_service_ticket_message(ticket_id,sender_type,sender_id,content,attachment_url,from_status,to_status,request_no) "
                  + "VALUES(?,'用户',?,?,?,'','待处理',?)", ticketId, customerId, content,
                safeUploadUrl(body.get("attachmentUrl")), normalizedRequestNo(body.get("requestNo"), "ticket"));
        return one("SELECT id,ticket_no AS ticketNo,category,content,contact,status,reply,create_time AS createTime "
                 + "FROM mall_service_ticket WHERE ticket_no=?", ticketNo);
    }

    public List<Map<String, Object>> serviceTickets(Long customerId)
    {
        ensureCustomer(customerId);
        return jdbc.queryForList("SELECT id,ticket_no AS ticketNo,category,content,contact,status,reply,reply_time AS replyTime," 
                + "create_time AS createTime,update_time AS updateTime FROM mall_service_ticket "
                + "WHERE customer_id=? ORDER BY id DESC LIMIT 100", customerId);
    }

    public Map<String, Object> serviceTicketDetail(Long customerId, String ticketNo)
    {
        ensureCustomer(customerId);
        Map<String, Object> ticket = one("SELECT id,ticket_no AS ticketNo,category,content,contact,status,reply,reply_time AS replyTime," 
                + "create_time AS createTime,update_time AS updateTime FROM mall_service_ticket "
                + "WHERE ticket_no=? AND customer_id=?", ticketNo, customerId);
        ticket.put("messages", jdbc.queryForList("SELECT id,sender_type AS senderType,content,attachment_url AS attachmentUrl," 
                + "from_status AS fromStatus,to_status AS toStatus,create_time AS createTime "
                + "FROM mall_service_ticket_message WHERE ticket_id=? ORDER BY create_time,id", ticket.get("id")));
        return ticket;
    }

    @Transactional
    public Map<String, Object> replyServiceTicket(Long customerId, String ticketNo, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        Map<String, Object> ticket = one("SELECT id,status FROM mall_service_ticket WHERE ticket_no=? AND customer_id=? FOR UPDATE",
                ticketNo, customerId);
        String content = required(body, "content").trim();
        if (content.length() > 1000) throw new IllegalArgumentException("回复内容不能超过1000字");
        String requestNo = normalizedRequestNo(body.get("requestNo"), "ticket-reply");
        String fromStatus = stringValue(ticket.get("status"));
        String toStatus = "已完成".equals(fromStatus) ? "处理中" : fromStatus;
        jdbc.update("INSERT IGNORE INTO mall_service_ticket_message(ticket_id,sender_type,sender_id,content,attachment_url,from_status,to_status,request_no) "
                  + "VALUES(?,'用户',?,?,?,?,?,?)", ticket.get("id"), customerId, content,
                safeUploadUrl(body.get("attachmentUrl")), fromStatus, toStatus, requestNo);
        jdbc.update("UPDATE mall_service_ticket SET status=? WHERE id=? AND status=?", toStatus, ticket.get("id"), fromStatus);
        return serviceTicketDetail(customerId, ticketNo);
    }

    @Transactional
    public Map<String, Object> exchange(Long customerId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        String requestId = required(body, "requestId").trim();
        if (!requestId.matches("^[A-Za-z0-9_-]{16,64}$"))
        {
            throw new IllegalArgumentException("兑换请求标识无效，请刷新后重试");
        }
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        Map<String, Object> existing = first("SELECT exchange_no AS exchangeNo,points_cost AS pointsCost,status,"
                + "receiver_name AS receiverName,receiver_phone AS receiverPhone,receiver_address AS receiverAddress "
                + "FROM mall_exchange WHERE customer_id=? AND request_id=? AND score_currency='POINTS'", customerId, requestId);
        if (existing != null) return existing;

        Long rewardId = longValue(body.get("rewardId"), null);
        int qty = intValue(body.get("qty"), 1);
        if (qty < 1) throw new IllegalArgumentException("兑换数量必须大于0");
        Long addressId = longValue(body.get("addressId"), null);
        Map<String, Object> address = addressId == null ? null : first(
                "SELECT * FROM mall_address WHERE id=? AND customer_id=?", addressId, customerId);
        requireCompleteAddress(address);
        Map<String, Object> reward = first("SELECT * FROM mall_reward WHERE id=? AND status='0' FOR UPDATE", rewardId);
        if (reward == null)
        {
            throw new IllegalArgumentException("积分商品不存在");
        }
        int limitQty = Math.max(1, intValue(reward.get("limit_qty"), 1));
        int exchangedQty = jdbc.queryForObject("SELECT COALESCE(SUM(qty),0) FROM mall_exchange WHERE customer_id=? "
                + "AND reward_id=? AND score_currency='POINTS' AND status<>'已取消'", Integer.class, customerId, rewardId);
        if ((long) exchangedQty + qty > limitQty) throw new IllegalArgumentException("该积分商品每人限兑" + limitQty + "件");
        int cost = checkedExchangeCost(intValue(reward.get("points"), 0), qty);
        int currentPoints = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId);
        if (currentPoints < cost) throw new IllegalArgumentException("积分不足");
        if (intValue(reward.get("stock"), 0) < qty) throw new IllegalArgumentException("库存不足");
        int changedStock = jdbc.update("UPDATE mall_reward SET stock=stock-? WHERE id=? AND stock>=?", qty, rewardId, qty);
        if (changedStock == 0)
        {
            throw new IllegalArgumentException("库存不足");
        }
        String exchangeNo = serial("E");
        jdbc.update("INSERT INTO mall_exchange(exchange_no,request_id,customer_id,reward_id,qty,points_cost,address_id,"
                + "receiver_name,receiver_phone,receiver_address,status) VALUES(?,?,?,?,?,?,?,?,?,?,'待处理')",
                exchangeNo, requestId, customerId, rewardId, qty, cost, addressId, address.get("receiver_name"),
                address.get("receiver_phone"), String.valueOf(address.get("region")) + " " + String.valueOf(address.get("detail_address")));
        boolean debited = addPointsUnique(customerId, -cost, "积分兑换", "兑换单号：" + exchangeNo + "；" + reward.get("name"),
                "EXCHANGE_DEBIT", requestId, null);
        if (!debited) throw new IllegalStateException("兑换请求已处理，但兑换记录不完整，请联系客服");
        return one("SELECT id,exchange_no AS exchangeNo,points_cost AS pointsCost,status,"
                + "receiver_name AS receiverName,receiver_phone AS receiverPhone,receiver_address AS receiverAddress "
                + "FROM mall_exchange WHERE customer_id=? AND request_id=?", customerId, requestId);
    }

    static int checkedExchangeCost(int unitPoints, int qty)
    {
        long total = (long) unitPoints * qty;
        if (unitPoints < 1 || qty < 1 || total > Integer.MAX_VALUE)
            throw new IllegalArgumentException("兑换积分总额无效或超出上限");
        return (int) total;
    }

    @Transactional
    public Map<String, Object> checkin(Long customerId)
    {
        ensureCustomer(customerId);
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        Map<String, Object> rule = one("SELECT * FROM mall_points_task_rule WHERE business_type='CHECKIN' AND status='0' "
                + "AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW()) ORDER BY sort_no,id LIMIT 1");
        String day = LocalDate.now(SHANGHAI_ZONE).toString();
        Map<String, Object> existing = first("SELECT streak_days AS streakDays,reward_points AS rewardPoints "
                + "FROM mall_points_checkin WHERE customer_id=? AND checkin_date=?", customerId, day);
        if (existing != null)
        {
            Map<String, Object> duplicate = new LinkedHashMap<String, Object>();
            duplicate.put("checkedIn", true);
            duplicate.put("added", 0);
            duplicate.put("streakDays", existing.get("streakDays"));
            duplicate.put("points", jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId));
            return duplicate;
        }
        String yesterday = LocalDate.now(SHANGHAI_ZONE).minusDays(1).toString();
        Integer priorStreak = jdbc.queryForObject("SELECT COALESCE(MAX(streak_days),0) FROM mall_points_checkin "
                + "WHERE customer_id=? AND checkin_date=?", Integer.class, customerId, yesterday);
        int streak = Math.max(1, (priorStreak == null ? 0 : priorStreak) + 1);
        int rewardPoints = Math.max(0, intValue(rule.get("reward_points"), 0));
        boolean added = addPointsUnique(customerId, rewardPoints, "每日签到", "连续签到第" + streak + "天",
                "DAILY_CHECKIN", day, null);
        if (!added) throw new IllegalStateException("签到流水已存在，请刷新页面");
        jdbc.update("INSERT INTO mall_points_checkin(customer_id,checkin_date,streak_days,reward_points,business_key) "
                + "VALUES(?,?,?,?,?)", customerId, day, streak, rewardPoints, day);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("checkedIn", true);
        result.put("added", rewardPoints);
        result.put("streakDays", streak);
        result.put("points", jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId));
        return result;
    }

    public Map<String, Object> pointsOverview(Long customerId)
    {
        ensureCustomer(customerId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        int balance = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId);
        String day = LocalDate.now(SHANGHAI_ZONE).toString();
        result.put("balance", balance);
        result.put("rewards",jdbc.queryForList("SELECT id,name,category,points,stock,stock_unit AS stockUnit,image_key AS imageKey FROM mall_reward WHERE status='0' AND points>0 ORDER BY id"));
        result.put("pendingPoints", jdbc.queryForObject("SELECT COALESCE(SUM(original_points-reversed_points),0) FROM mall_points_accrual WHERE customer_id=? AND status='待生效'", Integer.class, customerId));
        result.put("debtPoints", jdbc.queryForObject("SELECT COALESCE(SUM(points_amount-settled_points),0) FROM mall_points_debt WHERE customer_id=? AND status='待补扣'", Integer.class, customerId));
        result.put("checkedIn", jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_checkin WHERE customer_id=? AND checkin_date=?",
                Integer.class, customerId, day) > 0);
        result.put("streakDays", jdbc.queryForObject("SELECT COALESCE(MAX(streak_days),0) FROM mall_points_checkin WHERE customer_id=? "
                + "AND checkin_date=(SELECT MAX(checkin_date) FROM mall_points_checkin WHERE customer_id=?)", Integer.class, customerId, customerId));
        result.put("tasks", pointsTasks(customerId));
        result.put("consumptionRule", consumptionPointsConfig());
        result.put("tiers", tierRewards(customerId));
        result.put("ledger", ledgerSummary(customerId));
        return result;
    }

    public List<Map<String, Object>> pointsLogs(Long customerId)
    {
        ensureCustomer(customerId);
        return jdbc.queryForList("SELECT id,title,description AS `desc`,remark,business_type AS businessType,"
                + "business_key AS businessKey,related_business_no AS relatedBusinessNo,amount,"
                + "balance_before AS balanceBefore,balance_after AS balanceAfter,create_time AS createTime "
                + "FROM mall_points_log WHERE customer_id=? ORDER BY create_time DESC,id DESC", customerId);
    }

    public List<Map<String, Object>> pointsTasks(Long customerId)
    {
        ensureCustomer(customerId);
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT id,task_code AS taskCode,task_name AS taskName,business_type AS businessType,"
                + "reward_points AS rewardPoints,description,start_time AS startTime,end_time AS endTime,status "
                + "FROM mall_points_task_rule WHERE status='0' AND (start_time IS NULL OR start_time<=NOW()) "
                + "AND (end_time IS NULL OR end_time>=NOW()) ORDER BY sort_no,id");
        for (Map<String, Object> rule : rules)
        {
            Map<String, Object> qualification = taskQualification(customerId, stringValue(rule.get("businessType")));
            String businessKey = stringValue(qualification.get("businessKey"));
            boolean completed = Boolean.TRUE.equals(qualification.get("completed"));
            boolean claimed;
            if ("CHECKIN".equals(stringValue(rule.get("businessType"))))
                claimed = completed;
            else
                claimed = !businessKey.isEmpty() && jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_task_claim "
                        + "WHERE customer_id=? AND rule_id=? AND business_key=?", Integer.class,
                        customerId, rule.get("id"), businessKey) > 0;
            rule.put("completed", completed);
            rule.put("claimed", claimed);
            rule.put("claimable", completed && !claimed && !"CHECKIN".equals(stringValue(rule.get("businessType"))));
            rule.put("businessKey", businessKey);
        }
        return rules;
    }

    @Transactional
    public Map<String, Object> claimPointsTask(Long customerId, Long ruleId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        String requestNo = required(body, "requestNo").trim();
        if (!requestNo.matches("^[A-Za-z0-9_-]{16,64}$")) throw new IllegalArgumentException("领取请求标识无效");
        Map<String, Object> rule = one("SELECT * FROM mall_points_task_rule WHERE id=? AND status='0' "
                + "AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW()) FOR UPDATE", ruleId);
        String businessType = stringValue(rule.get("business_type"));
        if ("CHECKIN".equals(businessType)) throw new IllegalArgumentException("签到奖励请通过签到按钮领取");
        Map<String, Object> qualification = taskQualification(customerId, businessType);
        if (!Boolean.TRUE.equals(qualification.get("completed"))) throw new IllegalArgumentException("任务尚未完成");
        String businessKey = stringValue(qualification.get("businessKey"));
        Map<String, Object> existing = first("SELECT id,reward_points AS rewardPoints,status,1 AS idempotent FROM mall_points_task_claim "
                + "WHERE customer_id=? AND rule_id=? AND business_key=?", customerId, ruleId, businessKey);
        if (existing != null) return existing;
        int rewardPoints = Math.max(0, intValue(rule.get("reward_points"), 0));
        boolean credited = addPointsUnique(customerId, rewardPoints, "积分任务奖励", stringValue(rule.get("task_name")),
                "TASK_REWARD", stringValue(rule.get("task_code")) + ":" + businessKey, null);
        if (!credited) throw new IllegalStateException("该任务奖励已发放");
        jdbc.update("INSERT INTO mall_points_task_claim(customer_id,rule_id,business_key,reward_points,status) VALUES(?,?,?,?,'已领取')",
                customerId, ruleId, businessKey, rewardPoints);
        Map<String, Object> result = one("SELECT id,reward_points AS rewardPoints,status,0 AS idempotent FROM mall_points_task_claim "
                + "WHERE customer_id=? AND rule_id=? AND business_key=?", customerId, ruleId, businessKey);
        result.put("requestNo", requestNo);
        return result;
    }

    public List<Map<String, Object>> tierRewards(Long customerId)
    {
        ensureCustomer(customerId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,rule_name AS ruleName,metric_type AS metricType,"
                + "threshold_value AS thresholdValue,reward_points AS rewardPoints,reward_name AS rewardName,"
                + "reward_image_key AS rewardImageKey,reward_contents AS rewardContents,start_time AS startTime,end_time AS endTime,status "
                + "FROM mall_tier_reward_rule WHERE status='0' ORDER BY sort_no,threshold_value,id");
        for (Map<String, Object> row : rows)
        {
            int progress = tierProgress(customerId, stringValue(row.get("metricType")));
            boolean claimed = jdbc.queryForObject("SELECT COUNT(*) FROM mall_tier_reward_claim WHERE customer_id=? AND rule_id=?",
                    Integer.class, customerId, row.get("id")) > 0;
            boolean expired = row.get("endTime") != null && ((Date) row.get("endTime")).before(new Date());
            row.put("progress", progress);
            row.put("claimed", claimed);
            row.put("expired", expired);
            row.put("state", claimed ? "已领取" : expired ? "已过期" : progress >= intValue(row.get("thresholdValue"), 0) ? "可领取" : "未达成");
        }
        return rows;
    }

    public Map<String, Object> tierRewardDetail(Long customerId, Long ruleId)
    {
        return tierRewards(customerId).stream().filter(row -> ruleId.equals(longValue(row.get("id"), null))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("阶梯奖励不存在或已停用"));
    }

    @Transactional
    public Map<String, Object> claimTierReward(Long customerId, Long ruleId, Map<String, Object> body)
    {
        ensureCustomer(customerId);
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        String requestNo = required(body, "requestNo").trim();
        if (!requestNo.matches("^[A-Za-z0-9_-]{16,64}$")) throw new IllegalArgumentException("领取请求标识无效");
        Map<String, Object> existing = first("SELECT id,claim_no AS claimNo,reward_points AS rewardPoints,status,1 AS idempotent "
                + "FROM mall_tier_reward_claim WHERE customer_id=? AND rule_id=?", customerId, ruleId);
        if (existing != null) return existing;
        Map<String, Object> rule = one("SELECT * FROM mall_tier_reward_rule WHERE id=? AND status='0' "
                + "AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW()) FOR UPDATE", ruleId);
        int progress = tierProgress(customerId, stringValue(rule.get("metric_type")));
        if (progress < intValue(rule.get("threshold_value"), 0)) throw new IllegalArgumentException("阶梯奖励尚未达成");
        int rewardPoints = Math.max(0, intValue(rule.get("reward_points"), 0));
        String claimNo = serial("TR");
        if (rewardPoints > 0)
        {
            boolean credited = addPointsUnique(customerId, rewardPoints, "阶梯奖励", stringValue(rule.get("rule_name")),
                    "TIER_REWARD", String.valueOf(ruleId), null);
            if (!credited) throw new IllegalStateException("该阶梯奖励已发放");
        }
        jdbc.update("INSERT INTO mall_tier_reward_claim(claim_no,request_no,customer_id,rule_id,progress_snapshot,reward_points,reward_snapshot,status) "
                + "VALUES(?,?,?,?,?,?,?,'已领取')", claimNo, requestNo, customerId, ruleId, progress, rewardPoints,
                stringValue(rule.get("reward_name")) + "|" + stringValue(rule.get("reward_contents")));
        return one("SELECT id,claim_no AS claimNo,reward_points AS rewardPoints,status,0 AS idempotent "
                + "FROM mall_tier_reward_claim WHERE claim_no=?", claimNo);
    }

    public List<Map<String, Object>> rewardDetails(Long customerId)
    {
        ensureCustomer(customerId);
        return jdbc.queryForList("SELECT * FROM ("
                + "SELECT c.id,c.create_time AS createTime,'积分任务' AS source,r.task_name AS title,c.reward_points AS rewardPoints,c.status "
                + "FROM mall_points_task_claim c JOIN mall_points_task_rule r ON r.id=c.rule_id WHERE c.customer_id=? "
                + "UNION ALL SELECT c.id,c.create_time,'阶梯奖励',r.rule_name,c.reward_points,c.status "
                + "FROM mall_tier_reward_claim c JOIN mall_tier_reward_rule r ON r.id=c.rule_id WHERE c.customer_id=?"
                + ") x ORDER BY createTime DESC,id DESC", customerId, customerId);
    }

    public Map<String, Object> exchangeDetail(Long customerId, String exchangeNo)
    {
        ensureCustomer(customerId);
        return one("SELECT e.id,e.exchange_no AS exchangeNo,e.qty,e.points_cost AS pointsCost,e.score_currency AS scoreCurrency,e.invite_points_cost AS invitePointsCost,e.review_status AS reviewStatus,e.review_reason AS reviewReason,e.status,"
                + "e.receiver_name AS receiverName,e.receiver_phone AS receiverPhone,e.receiver_address AS receiverAddress,"
                + "e.carrier,e.tracking_no AS trackingNo,e.received_time AS receivedTime,e.create_time AS createTime,e.update_time AS updateTime,"
                + "r.id AS rewardId,r.name,r.image_key AS imageKey,r.delivery_method AS deliveryMethod,r.exchange_notes AS exchangeNotes "
                + "FROM mall_exchange e JOIN mall_reward r ON r.id=e.reward_id WHERE e.customer_id=? AND e.exchange_no=?", customerId, exchangeNo);
    }

    @Transactional
    public Map<String, Object> cancelExchange(Long customerId, String exchangeNo)
    {
        ensureCustomer(customerId);
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        Map<String, Object> exchange = one("SELECT * FROM mall_exchange WHERE customer_id=? AND exchange_no=? FOR UPDATE", customerId, exchangeNo);
        if ("INVITE".equals(exchange.get("score_currency"))) {
            teaFriendService.cancel(customerId,exchangeNo);
            return exchangeDetail(customerId,exchangeNo);
        }
        String status = stringValue(exchange.get("status"));
        if ("已取消".equals(status))
        {
            Map<String, Object> result = exchangeDetail(customerId, exchangeNo); result.put("idempotent", 1); return result;
        }
        if (!("待处理".equals(status) || "待发货".equals(status))) throw new IllegalArgumentException("当前兑换状态不能取消");
        jdbc.update("UPDATE mall_reward SET stock=stock+? WHERE id=?", exchange.get("qty"), exchange.get("reward_id"));
        boolean restored = addPointsUnique(customerId, intValue(exchange.get("points_cost"), 0), "兑换取消退回", "兑换单号：" + exchangeNo,
                "EXCHANGE_REFUND", exchangeNo, null);
        if (!restored) throw new IllegalStateException("兑换退回流水已存在但状态异常");
        jdbc.update("UPDATE mall_exchange SET status='已取消' WHERE id=?", exchange.get("id"));
        Map<String, Object> result = exchangeDetail(customerId, exchangeNo); result.put("idempotent", 0); return result;
    }

    @Transactional
    public Map<String, Object> confirmExchangeReceipt(Long customerId, String exchangeNo)
    {
        ensureCustomer(customerId);
        Map<String, Object> exchange = one(
                "SELECT * FROM mall_exchange WHERE customer_id=? AND exchange_no=? FOR UPDATE",
                customerId, exchangeNo);
        String status = stringValue(exchange.get("status"));
        if ("已完成".equals(status))
        {
            Map<String, Object> result = exchangeDetail(customerId, exchangeNo);
            result.put("idempotent", 1);
            return result;
        }
        if (!("配送中".equals(status) || "待收货".equals(status)))
            throw new IllegalArgumentException("只有配送中的兑换单可以确认收货");
        jdbc.update("UPDATE mall_exchange SET status='已完成',received_time=COALESCE(received_time,NOW()) WHERE id=?",
                exchange.get("id"));
        notifyCustomer(customerId, "积分", "兑换礼品已确认收货", "兑换单" + exchangeNo + "已完成",
                "/pages/my-exchanges/my-exchanges", "exchangeNo=" + exchangeNo, "EXCHANGE", exchangeNo,
                "EXCHANGE_RECEIVED");
        Map<String, Object> result = exchangeDetail(customerId, exchangeNo);
        result.put("idempotent", 0);
        return result;
    }

    public void addFavorite(Long customerId, Long productId)
    {
        jdbc.update("INSERT IGNORE INTO mall_favorite(customer_id,product_id) VALUES(?,?)", customerId, productId);
    }

    public void removeFavorite(Long customerId, Long productId)
    {
        jdbc.update("DELETE FROM mall_favorite WHERE customer_id=? AND product_id=?", customerId, productId);
    }

    public void addTopicFavorite(Long customerId, Long topicId)
    {
        one("SELECT id FROM mall_topic WHERE id=? AND status='0'", topicId);
        jdbc.update("INSERT IGNORE INTO mall_topic_favorite(customer_id,topic_id) VALUES(?,?)", customerId, topicId);
    }

    public void removeTopicFavorite(Long customerId, Long topicId)
    {
        jdbc.update("DELETE FROM mall_topic_favorite WHERE customer_id=? AND topic_id=?", customerId, topicId);
    }

    public List<Map<String, Object>> listNotifications(Long customerId)
    {
        return jdbc.queryForList("SELECT id,category,title,content,target_route AS targetRoute,target_query AS targetQuery,"
                + "source_type AS sourceType,source_key AS sourceKey,event_code AS eventCode,read_status AS readStatus,"
                + "read_time AS readTime,create_time AS createTime FROM mall_notification "
                + "WHERE customer_id=? ORDER BY create_time DESC,id DESC LIMIT 100", customerId);
    }

    public void markNotificationRead(Long customerId, Long id)
    {
        int changed = jdbc.update("UPDATE mall_notification SET read_status=1,read_time=COALESCE(read_time,NOW()) "
                + "WHERE id=? AND customer_id=?", id, customerId);
        if (changed == 0) throw new IllegalArgumentException("消息不存在");
    }

    public void markAllNotificationsRead(Long customerId)
    {
        jdbc.update("UPDATE mall_notification SET read_status=1,read_time=COALESCE(read_time,NOW()) "
                + "WHERE customer_id=? AND read_status=0", customerId);
    }

	@Transactional
	public Map<String, Object> updateProfile(Long customerId, Map<String, Object> body)
	{
		ensureCustomer(customerId);
		Map<String, Object> current = one("SELECT nickname,phone,avatar_url FROM mall_customer WHERE id=?", customerId);
		String nickname = body.containsKey("nickname") ? required(body, "nickname") : stringValue(current.get("nickname"));
		if (nickname.length() > 12)
		{
			throw new IllegalArgumentException("昵称不能超过12个字");
		}
		String phone = stringValue(current.get("phone"));
		if (body.containsKey("phone") && !phone.equals(stringValue(body.get("phone")).trim()))
			throw new IllegalArgumentException("修改手机号必须通过短信验证");
		String avatarUrl = body.containsKey("avatarUrl") ? stringValue(body.get("avatarUrl")) : stringValue(current.get("avatar_url"));
		jdbc.update("UPDATE mall_customer SET nickname=?,phone=?,avatar_url=? WHERE id=?", nickname, phone, avatarUrl, customerId);
		return customerProfile(customerId);
	}

    @Transactional
    public void updatePassword(Long customerId, Map<String, Object> body)
    {
        String currentPassword = stringValue(body.get("currentPassword"));
        String newPassword = required(body, "newPassword");
        if (!newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,32}$"))
            throw new IllegalArgumentException("新密码需为8-32位，且同时包含字母和数字");
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM mall_customer WHERE id=?", String.class, customerId);
        if (passwordHash != null && !passwordHash.isEmpty()
                && !SecurityUtils.matchesPassword(currentPassword, passwordHash))
            throw new IllegalArgumentException("原密码不正确");
        jdbc.update("UPDATE mall_customer SET password_hash=? WHERE id=?", SecurityUtils.encryptPassword(newPassword), customerId);
    }

    public Map<String, Object> updateAvatar(Long customerId, String avatarUrl)
    {
        jdbc.update("UPDATE mall_customer SET avatar_url=? WHERE id=?", avatarUrl, customerId);
        return customerProfile(customerId);
    }

    @Transactional
    public Map<String, Object> submitReview(Long customerId, Map<String, Object> body)
    {
        String orderNo = required(body, "orderNo");
        Long productId = longValue(body.get("productId"), null);
		if (productId == null || productId <= 0) throw new IllegalArgumentException("请选择需要评价的订单商品");
		int rating = intValue(body.get("rating"), 0);
		if (rating < 1 || rating > 5) throw new IllegalArgumentException("评分必须为1-5星");
        String content = required(body, "content").trim();
        if (content.length() < 5 || content.length() > 500) throw new IllegalArgumentException("评价内容需为5-500个字");
        Map<String, Object> item = first("SELECT i.id AS itemId,o.id AS orderId,o.status FROM mall_order o "
                + "JOIN mall_order_item i ON i.order_id=o.id WHERE o.customer_id=? AND o.order_no=? AND i.product_id=? LIMIT 1",
                customerId, orderNo, productId);
        if (item == null || !"已完成".equals(String.valueOf(item.get("status"))))
            throw new IllegalArgumentException("仅已完成订单可以评价");
		// order_item_id has a unique key. Keeping the first submitted review makes
		// retries and rapid double taps idempotent instead of returning a 500 error.
		jdbc.update("INSERT INTO mall_review(order_id,order_item_id,product_id,customer_id,rating,content,status) VALUES(?,?,?,?,?,?,'0') "
				+ "ON DUPLICATE KEY UPDATE id=id",
                item.get("orderId"), item.get("itemId"), productId, customerId, rating, content);
        addPointsUnique(customerId, 10, "评价商品", "订单号：" + orderNo,
                "PRODUCT_REVIEW", String.valueOf(item.get("itemId")), ((Number) item.get("orderId")).longValue());
        // Do not depend on connection-scoped LAST_INSERT_ID(): pooled connections may
        // execute the follow-up query on another physical connection. order_item_id is
        // unique and therefore gives us a deterministic read-after-write.
        return one("SELECT id,product_id AS productId,rating,content,create_time AS createTime "
                + "FROM mall_review WHERE order_item_id=?", item.get("itemId"));
    }

    private Map<String, Object> customerProfile(Long customerId)
    {
        return one("SELECT id,nickname,phone,avatar_url AS avatarUrl,points,status,"
             + "CASE WHEN password_hash<>'' THEN 1 ELSE 0 END AS hasPassword FROM mall_customer WHERE id=?", customerId);
    }

    public Map<String, Object> health()
    {
        Integer database = jdbc.queryForObject("SELECT 1", Integer.class);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", database != null && database == 1 ? "UP" : "DOWN");
        result.put("database", database != null && database == 1 ? "UP" : "DOWN");
        result.put("sellableProducts", jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_product WHERE status='0'", Integer.class));
        return result;
    }

    public Map<String, Object> overview()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", count("mall_product"));
        result.put("orders", jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE is_test_order=0", Long.class));
        result.put("customers", jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer WHERE status<>'2'", Long.class));
        result.put("aftersales", count("mall_aftersale"));
        result.put("salesAmount", jdbc.queryForObject("SELECT COALESCE(SUM(paid_amount),0) FROM mall_order WHERE is_test_order=0 AND status NOT IN ('已取消','已退款')", BigDecimal.class));
        return result;
    }

    public List<Map<String, Object>> adminProducts()
    {
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT " + PRODUCT_COLUMNS + ", p.create_time AS createTime FROM mall_product p ORDER BY p.id");
        attachProductCatalog(products);
        return products;
    }

    public List<Map<String, Object>> adminCategories()
    {
        return jdbc.queryForList("SELECT c.id,c.parent_id AS parentId,c.category_code AS categoryCode,c.category_group AS categoryGroup,c.name,c.icon_url AS iconUrl,c.sort_no AS sortNo,c.status,c.create_time AS createTime,"
                + "(SELECT COUNT(*) FROM mall_product p WHERE p.category_id=c.id) AS productCount "
                + "FROM mall_category c ORDER BY c.sort_no,c.id");
    }

    @Transactional
    public Long adminCreateCategory(Map<String, Object> body)
    {
        String name = required(body, "name").trim();
        if (name.length() > 32) throw new IllegalArgumentException("分类名称不能超过32个字");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_category WHERE name=?", Integer.class, name) > 0)
            throw new IllegalArgumentException("分类名称已存在");
        Long parentId=longValue(body.get("parentId"),null);String code=stringValue(body.get("categoryCode")).trim();String group=stringValue(body.get("categoryGroup")).trim();
        if(code.isEmpty())code="CAT_"+System.currentTimeMillis();if(group.isEmpty())group="TEA";
        validateProductCategoryParent(null,parentId,group);
        jdbc.update("INSERT INTO mall_category(parent_id,category_code,category_group,name,icon_url,sort_no,status) VALUES(?,?,?,?,?,?,?)",parentId,code,group,name,
                stringValue(body.get("iconUrl")), intValue(body.get("sortNo"), 0), stringValue(body.get("status")));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Transactional
    public void adminUpdateCategory(Long id, Map<String, Object> body)
    {
        Map<String, Object> old = one("SELECT name FROM mall_category WHERE id=?", id);
        String name = required(body, "name").trim();
        if (!name.equals(stringValue(old.get("name"))) && jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_category WHERE name=? AND id<>?", Integer.class, name, id) > 0)
            throw new IllegalArgumentException("分类名称已存在");
        Long parentId=longValue(body.get("parentId"),null);String group=stringValue(body.get("categoryGroup")).trim();
        validateProductCategoryParent(id,parentId,group);
        jdbc.update("UPDATE mall_category SET parent_id=?,category_code=?,category_group=?,name=?,icon_url=?,sort_no=?,status=? WHERE id=?",parentId,
                stringValue(body.get("categoryCode")),group,name,stringValue(body.get("iconUrl")),intValue(body.get("sortNo"),0),stringValue(body.get("status")),id);
        if (!name.equals(stringValue(old.get("name"))))
            jdbc.update("UPDATE mall_product SET category=? WHERE category_id=?", name, id);
    }

    public void adminDeleteCategory(Long id)
    {
        Map<String, Object> category = one("SELECT name FROM mall_category WHERE id=?", id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE category_id=?", Integer.class, id) > 0)
            throw new IllegalArgumentException("该分类已有商品，不能删除，请改为隐藏");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_category WHERE parent_id=?",Integer.class,id)>0)
            throw new IllegalArgumentException("该分类下存在子分类，不能删除，请先停用或迁移子分类");
        jdbc.update("DELETE FROM mall_category WHERE id=?", id);
    }

    public List<Map<String, Object>> adminTopics()
    {
        return jdbc.queryForList("SELECT t.id,t.slug,t.title,t.kicker,t.subtitle,t.hero_image_url AS heroImageUrl,"
                + "t.story_image_url AS storyImageUrl,t.story_title AS storyTitle,t.story_content AS storyContent,"
                + "t.start_time AS startTime,t.end_time AS endTime,t.status,t.sort_no AS sortNo,t.create_time AS createTime,"
                + "COALESCE((SELECT GROUP_CONCAT(tp.product_id ORDER BY tp.sort_no,tp.product_id SEPARATOR ',') "
                + "FROM mall_topic_product tp WHERE tp.topic_id=t.id),'') AS productIds FROM mall_topic t ORDER BY t.sort_no,t.id");
    }

    @Transactional
    public Long adminCreateTopic(Map<String, Object> body)
    {
        String slug = required(body, "slug").trim();
        validateSlug(slug);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_topic WHERE slug=?", Integer.class, slug) > 0)
            throw new IllegalArgumentException("专题标识已存在");
        jdbc.update("INSERT INTO mall_topic(slug,title,kicker,subtitle,hero_image_url,story_image_url,story_title,story_content,"
                + "start_time,end_time,status,sort_no) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", slug, required(body,"title"),
                stringValue(body.get("kicker")),stringValue(body.get("subtitle")),stringValue(body.get("heroImageUrl")),
                stringValue(body.get("storyImageUrl")),stringValue(body.get("storyTitle")),stringValue(body.get("storyContent")),
                emptyToNull(body.get("startTime")),emptyToNull(body.get("endTime")),stringValue(body.get("status")),
                intValue(body.get("sortNo"),0));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        syncTopicProducts(id, body.get("productIds"));
        return id;
    }

    @Transactional
    public void adminUpdateTopic(Long id, Map<String, Object> body)
    {
        one("SELECT id FROM mall_topic WHERE id=?", id);
        String slug = required(body, "slug").trim();
        validateSlug(slug);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_topic WHERE slug=? AND id<>?", Integer.class, slug, id) > 0)
            throw new IllegalArgumentException("专题标识已存在");
        jdbc.update("UPDATE mall_topic SET slug=?,title=?,kicker=?,subtitle=?,hero_image_url=?,story_image_url=?,story_title=?,"
                + "story_content=?,start_time=?,end_time=?,status=?,sort_no=? WHERE id=?", slug,required(body,"title"),
                stringValue(body.get("kicker")),stringValue(body.get("subtitle")),stringValue(body.get("heroImageUrl")),
                stringValue(body.get("storyImageUrl")),stringValue(body.get("storyTitle")),stringValue(body.get("storyContent")),
                emptyToNull(body.get("startTime")),emptyToNull(body.get("endTime")),stringValue(body.get("status")),
                intValue(body.get("sortNo"),0),id);
        syncTopicProducts(id, body.get("productIds"));
    }

    @Transactional
    public void adminDeleteTopic(Long id)
    {
        one("SELECT id FROM mall_topic WHERE id=?", id);
        jdbc.update("DELETE FROM mall_topic_favorite WHERE topic_id=?", id);
        jdbc.update("DELETE FROM mall_topic_product WHERE topic_id=?", id);
        jdbc.update("DELETE FROM mall_topic WHERE id=?", id);
    }

    public List<Map<String, Object>> adminNotifications()
    {
        return jdbc.queryForList("SELECT n.id,n.customer_id AS customerId,c.nickname,c.phone,n.category,n.title,n.content,"
                + "n.target_route AS targetRoute,n.target_query AS targetQuery,n.source_type AS sourceType,"
                + "n.source_key AS sourceKey,n.event_code AS eventCode,n.read_status AS readStatus,"
                + "n.read_time AS readTime,n.create_time AS createTime FROM mall_notification n "
                + "JOIN mall_customer c ON c.id=n.customer_id ORDER BY n.id DESC LIMIT 500");
    }

    public Long adminCreateNotification(Map<String, Object> body)
    {
        Long customerId = longValue(body.get("customerId"), null);
        ensureCustomer(customerId);
        String category = required(body, "category");
        String title = required(body, "title");
        String content = required(body, "content");
        if (title.length() > 128 || content.length() > 1000)
            throw new IllegalArgumentException("消息标题或内容过长");
        jdbc.update("INSERT INTO mall_notification(customer_id,category,title,content,target_route,target_query,"
                + "source_type,source_key,event_code) VALUES(?,?,?,?,?,?,?,?,?)", customerId, category, title, content,
                stringValue(body.get("targetRoute")), stringValue(body.get("targetQuery")), "ADMIN",
                serial("M"), "PUBLISHED");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void adminUpdateNotification(Long id, Map<String, Object> body)
    {
        one("SELECT id FROM mall_notification WHERE id=?", id);
        jdbc.update("UPDATE mall_notification SET category=?,title=?,content=?,target_route=?,target_query=? WHERE id=?",
                required(body,"category"),required(body,"title"),required(body,"content"),
                stringValue(body.get("targetRoute")),stringValue(body.get("targetQuery")),id);
    }

    public void adminDeleteNotification(Long id)
    {
        jdbc.update("DELETE FROM mall_notification WHERE id=? AND source_type='ADMIN'", id);
    }

    public List<Map<String, Object>> adminStores()
    {
        return jdbc.queryForList("SELECT s.id,s.name,s.logo_url AS logoUrl,s.hero_image_url AS heroImageUrl,s.rating,"
                + "s.follower_count AS followerCount,s.story,s.shipping_promise AS shippingPromise,s.service_promise AS servicePromise,"
                + "s.status,s.create_time AS createTime,(SELECT COUNT(*) FROM mall_product p WHERE p.store_id=s.id) AS productCount,"
                + "(SELECT COUNT(*) FROM mall_store_favorite f WHERE f.store_id=s.id) AS memberFollowerCount FROM mall_store s ORDER BY s.id");
    }

    public Long adminCreateStore(Map<String, Object> body)
    {
        jdbc.update("INSERT INTO mall_store(name,logo_url,hero_image_url,rating,follower_count,story,shipping_promise,service_promise,status) "
                + "VALUES(?,?,?,?,?,?,?,?,?)",required(body,"name"),stringValue(body.get("logoUrl")),
                stringValue(body.get("heroImageUrl")),decimalValue(body.get("rating")),intValue(body.get("followerCount"),0),
                stringValue(body.get("story")),stringValue(body.get("shippingPromise")),stringValue(body.get("servicePromise")),
                stringValue(body.get("status")));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void adminUpdateStore(Long id, Map<String, Object> body)
    {
        one("SELECT id FROM mall_store WHERE id=?", id);
        jdbc.update("UPDATE mall_store SET name=?,logo_url=?,hero_image_url=?,rating=?,follower_count=?,story=?,shipping_promise=?,"
                + "service_promise=?,status=? WHERE id=?",required(body,"name"),stringValue(body.get("logoUrl")),
                stringValue(body.get("heroImageUrl")),decimalValue(body.get("rating")),intValue(body.get("followerCount"),0),
                stringValue(body.get("story")),stringValue(body.get("shippingPromise")),stringValue(body.get("servicePromise")),
                stringValue(body.get("status")),id);
    }

    @Transactional
    public void adminDeleteStore(Long id)
    {
        one("SELECT id FROM mall_store WHERE id=?", id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE store_id=?", Integer.class, id) > 0)
            throw new IllegalArgumentException("店铺已有商品，不能删除，请改为停用");
        jdbc.update("DELETE FROM mall_store_favorite WHERE store_id=?", id);
        jdbc.update("DELETE FROM mall_store WHERE id=?", id);
    }

    public List<Map<String, Object>> adminSkus()
    {
        return jdbc.queryForList("SELECT s.id,s.id AS skuId,s.product_id AS productId,p.name AS productName,s.sku_code AS skuCode,"
                + "s.spec_name AS spec,s.price,s.reward_points AS reward,s.stock,s.sales,s.is_default AS isDefault,s.status,"
                + "s.create_time AS createTime FROM mall_product_sku s JOIN mall_product p ON p.id=s.product_id ORDER BY s.product_id,s.is_default DESC,s.id");
    }

    @Transactional
    public Long adminCreateSku(Map<String, Object> body)
    {
        validateProductNumbers(body);
        Long productId = longValue(body.get("productId"), null);
        requireProductAnyStatus(productId);
        String spec = required(body, "spec").trim();
        String skuCode = required(body, "skuCode").trim();
        requireUniqueSku(null, productId, skuCode, spec);
        int isDefault = boolValue(body.get("isDefault"), false) ? 1 : 0;
        if (isDefault == 1) jdbc.update("UPDATE mall_product_sku SET is_default=0 WHERE product_id=?", productId);
        BigDecimal price = decimalValue(body.get("price"));
        jdbc.update("INSERT INTO mall_product_sku(product_id,sku_code,spec_name,price,reward_points,stock,sales,is_default,status) "
                + "VALUES(?,?,?,?,?,?,?,?,?)",productId,skuCode,spec,price,rewardPoints(price),intValue(body.get("stock"),0),0,
                isDefault,stringValue(body.get("status")));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        ensureDefaultSku(productId);
        syncProductFromSkus(productId);
        return id;
    }

    @Transactional
    public void adminUpdateSku(Long id, Map<String, Object> body)
    {
        validateProductNumbers(body);
        Map<String, Object> old = one("SELECT product_id FROM mall_product_sku WHERE id=? FOR UPDATE", id);
        Long productId = longValue(old.get("product_id"), null);
        String spec = required(body, "spec").trim();
        String skuCode = required(body, "skuCode").trim();
        requireUniqueSku(id, productId, skuCode, spec);
        String status = stringValue(body.get("status"));
        int activeOthers = jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE product_id=? AND id<>? AND status='0'", Integer.class, productId, id);
        if (!"0".equals(status) && activeOthers == 0) throw new IllegalArgumentException("每个商品至少保留一个在售规格");
        int isDefault = boolValue(body.get("isDefault"), false) ? 1 : 0;
        if (isDefault == 1) jdbc.update("UPDATE mall_product_sku SET is_default=0 WHERE product_id=?", productId);
        BigDecimal price = decimalValue(body.get("price"));
        jdbc.update("UPDATE mall_product_sku SET sku_code=?,spec_name=?,price=?,reward_points=?,stock=?,is_default=?,status=? WHERE id=?",
                skuCode,spec,price,rewardPoints(price),intValue(body.get("stock"),0),isDefault,status,id);
        ensureDefaultSku(productId);
        syncProductFromSkus(productId);
    }

    @Transactional
    public void adminDeleteSku(Long id)
    {
        Map<String, Object> sku = one("SELECT product_id FROM mall_product_sku WHERE id=?", id);
        Long productId = longValue(sku.get("product_id"), null);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_order_item WHERE sku_id=?", Integer.class, id) > 0)
            throw new IllegalArgumentException("该规格已有订单，不能删除，请改为停用");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE product_id=?", Integer.class, productId) <= 1)
            throw new IllegalArgumentException("商品必须至少保留一个规格");
        jdbc.update("DELETE FROM mall_cart WHERE sku_id=?", id);
        jdbc.update("DELETE FROM mall_product_sku WHERE id=?", id);
        ensureDefaultSku(productId);
        syncProductFromSkus(productId);
    }

    public List<Map<String, Object>> adminOrders()
    {
        List<Map<String, Object>> orders = jdbc.queryForList("SELECT o.id, o.order_no AS orderNo, c.nickname, o.status, o.total_amount AS totalAmount, "
                + "o.shipping_fee AS shippingFee,o.discount_amount AS discountAmount,o.points_discount AS pointsDiscount,o.points_used AS pointsUsed,"
                + "o.paid_amount AS paidAmount,o.payment_method AS paymentMethod,o.payment_status AS paymentStatus,o.paid_time AS paidTime,o.is_test_order AS isTestOrder,"
                + "o.reward_points AS rewardPoints, o.receiver_name AS receiverName, o.receiver_phone AS receiverPhone, "
                + "o.receiver_address AS receiverAddress, o.carrier, o.tracking_no AS trackingNo,o.remark,o.create_time AS createTime,"
                + "o.cancel_reason AS cancelReason,o.cancel_note AS cancelNote,o.cancel_time AS cancelTime,"
                + "o.aftersale_status AS aftersaleStatus,o.refunded_amount AS refundedAmount "
                + "FROM mall_order o JOIN mall_customer c ON c.id=o.customer_id ORDER BY o.id DESC");
        for (Map<String, Object> order : orders)
        {
            List<Map<String, Object>> items = jdbc.queryForList("SELECT product_name AS name,spec,price,qty,price*qty AS subtotal "
                    + "FROM mall_order_item WHERE order_id=? ORDER BY id", order.get("id"));
            order.put("items", items);
            StringBuilder summary = new StringBuilder();
            int itemCount = 0;
            for (Map<String, Object> item : items)
            {
                if (summary.length() > 0) summary.append("；");
                summary.append(item.get("name")).append(" ").append(item.get("spec")).append(" ×").append(item.get("qty"));
                itemCount += intValue(item.get("qty"), 0);
            }
            order.put("itemSummary", summary.toString());
            order.put("itemCount", itemCount);
            order.put("operations", jdbc.queryForList("SELECT operator_type AS operatorType,source_name AS sourceName,old_status AS oldStatus,"
                    + "new_status AS newStatus,reason,request_no AS requestNo,create_time AS createTime "
                    + "FROM mall_order_operation_log WHERE order_id=? ORDER BY id", order.get("id")));
        }
        return orders;
    }

    public List<Map<String, Object>> adminAftersales()
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id, a.aftersale_no AS aftersaleNo, o.order_no AS orderNo, c.nickname, "
                + "a.type_name AS typeName, a.reason,a.description,a.apply_qty AS qty,a.status,a.requested_amount AS requestedAmount,a.refund_amount AS refundAmount,"
                + "a.admin_remark AS adminRemark,a.refund_no AS refundNo,a.refund_time AS refundTime,o.paid_amount AS paidAmount,"
                + "o.receiver_name AS receiverName,o.receiver_phone AS receiverPhone,o.receiver_address AS receiverAddress,"
                + "a.return_address AS returnAddress,a.return_carrier AS returnCarrier,a.return_tracking_no AS returnTrackingNo,"
                + "a.exchange_carrier AS exchangeCarrier,a.exchange_tracking_no AS exchangeTrackingNo,"
                + "i.product_name AS productName,i.spec,i.image_key AS imageKey,"
                + "a.create_time AS createTime FROM mall_aftersale a "
                + "JOIN mall_order o ON o.id=a.order_id JOIN mall_customer c ON c.id=a.customer_id "
                + "LEFT JOIN mall_order_item i ON i.id=a.order_item_id ORDER BY a.id DESC");
        for (Map<String, Object> row : rows)
        {
            row.put("operations", jdbc.queryForList("SELECT operator_type AS operatorType,source_name AS sourceName,old_status AS oldStatus,"
                    + "new_status AS newStatus,remark,request_no AS requestNo,create_time AS createTime "
                    + "FROM mall_aftersale_operation_log WHERE aftersale_id=? ORDER BY id", row.get("id")));
        }
        return rows;
    }

    public List<Map<String, Object>> adminRewards()
    {
        return jdbc.queryForList("SELECT id,name,category,points,stock,limit_qty AS limitQty,stock_unit AS stockUnit,"
                + "image_key AS imageKey,exchange_notes AS exchangeNotes,delivery_method AS deliveryMethod,status,"
                + "create_time AS createTime FROM mall_reward ORDER BY id");
    }

    @Transactional
    public Long adminCreateReward(Map<String, Object> body)
    {
        int points = intValue(body.get("points"), 0);
        int stock = intValue(body.get("stock"), 0);
        int limitQty = intValue(body.get("limitQty"), 1);
        if (points < 1 || stock < 0 || limitQty < 1) throw new IllegalArgumentException("积分价格、库存或限购数量无效");
        jdbc.update("INSERT INTO mall_reward(name,category,points,stock,limit_qty,stock_unit,image_key,exchange_notes,delivery_method,status) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)", required(body,"name"), stringValue(body.get("category")), points,
                stock, limitQty, stringValue(body.get("stockUnit")), stringValue(body.get("imageKey")),
                stringValue(body.get("exchangeNotes")), stringValue(body.get("deliveryMethod")), stringValue(body.get("status")));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Map<String, Object>> adminPointsTasks()
    {
        return jdbc.queryForList("SELECT id,task_code AS taskCode,task_name AS taskName,business_type AS businessType,"
                + "reward_points AS rewardPoints,description,start_time AS startTime,end_time AS endTime,status,sort_no AS sortNo,"
                + "(SELECT COUNT(*) FROM mall_points_task_claim c WHERE c.rule_id=r.id) AS claimCount "
                + "FROM mall_points_task_rule r ORDER BY sort_no,id");
    }

    public void adminUpdatePointsTask(Long id, Map<String, Object> body)
    {
        int rewardPoints = intValue(body.get("rewardPoints"), 0);
        if (rewardPoints < 1) throw new IllegalArgumentException("任务积分必须大于0");
        int updated = jdbc.update("UPDATE mall_points_task_rule SET task_name=?,reward_points=?,description=?,start_time=?,end_time=?,status=?,sort_no=? WHERE id=?",
                required(body,"taskName"), rewardPoints, stringValue(body.get("description")),
                emptyToNull(body.get("startTime")), emptyToNull(body.get("endTime")), stringValue(body.get("status")),
                intValue(body.get("sortNo"), 0), id);
        if (updated == 0) throw new IllegalArgumentException("积分任务不存在");
    }

    public List<Map<String, Object>> adminPointsLedger()
    {
        return jdbc.queryForList("SELECT l.id,l.customer_id AS customerId,c.nickname,c.phone,l.title,l.description,l.remark,"
                + "l.business_type AS businessType,l.business_key AS businessKey,l.related_business_no AS relatedBusinessNo,"
                + "l.amount,l.balance_before AS balanceBefore,l.balance_after AS balanceAfter,l.create_time AS createTime "
                + "FROM mall_points_log l JOIN mall_customer c ON c.id=l.customer_id ORDER BY l.id DESC");
    }

    public List<Map<String, Object>> adminPointsAccruals()
    {
        return jdbc.queryForList("SELECT a.id,a.customer_id AS customerId,c.nickname,c.phone,a.order_no AS orderNo,"
                + "a.original_points AS originalPoints,a.available_points AS availablePoints,a.reversed_points AS reversedPoints,"
                + "a.status,a.effective_time AS effectiveTime,a.create_time AS createTime,a.update_time AS updateTime "
                + "FROM mall_points_accrual a JOIN mall_customer c ON c.id=a.customer_id ORDER BY a.id DESC");
    }

    public List<Map<String, Object>> adminPointsDebts()
    {
        return jdbc.queryForList("SELECT d.id,d.debt_no AS debtNo,d.customer_id AS customerId,c.nickname,c.phone,"
                + "d.business_no AS businessNo,d.points_amount AS pointsAmount,d.settled_points AS settledPoints,"
                + "d.status,d.reason,d.create_time AS createTime,d.update_time AS updateTime "
                + "FROM mall_points_debt d JOIN mall_customer c ON c.id=d.customer_id ORDER BY d.id DESC");
    }

    public List<Map<String, Object>> adminPointCustomers()
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,nickname,phone,points FROM mall_customer WHERE status<>'2' ORDER BY id DESC");
        for (Map<String, Object> row : rows) row.put("ledger", ledgerSummary(longValue(row.get("id"), 0L)));
        return rows;
    }

    public List<Map<String, Object>> adminTierRules()
    {
        return jdbc.queryForList("SELECT r.id,r.rule_name AS ruleName,r.metric_type AS metricType,r.threshold_value AS thresholdValue,"
                + "r.reward_points AS rewardPoints,r.reward_name AS rewardName,r.reward_image_key AS rewardImageKey,"
                + "r.reward_contents AS rewardContents,r.start_time AS startTime,r.end_time AS endTime,r.status,r.sort_no AS sortNo,"
                + "(SELECT COUNT(*) FROM mall_tier_reward_claim c WHERE c.rule_id=r.id) AS claimCount "
                + "FROM mall_tier_reward_rule r ORDER BY r.sort_no,r.threshold_value,r.id");
    }

    public void adminUpdateTierRule(Long id, Map<String, Object> body)
    {
        int threshold = intValue(body.get("thresholdValue"), 0);
        int rewardPoints = intValue(body.get("rewardPoints"), 0);
        if (threshold < 1 || rewardPoints < 0) throw new IllegalArgumentException("阶梯门槛或奖励积分无效");
        int updated = jdbc.update("UPDATE mall_tier_reward_rule SET rule_name=?,threshold_value=?,reward_points=?,reward_name=?,"
                        + "reward_image_key=?,reward_contents=?,start_time=?,end_time=?,status=?,sort_no=? WHERE id=?",
                required(body,"ruleName"), threshold, rewardPoints, required(body,"rewardName"), stringValue(body.get("rewardImageKey")),
                stringValue(body.get("rewardContents")), emptyToNull(body.get("startTime")), emptyToNull(body.get("endTime")),
                stringValue(body.get("status")), intValue(body.get("sortNo"), 0), id);
        if (updated == 0) throw new IllegalArgumentException("阶梯规则不存在");
    }

    public List<Map<String, Object>> adminTierClaims()
    {
        return jdbc.queryForList("SELECT c.id,c.claim_no AS claimNo,u.nickname,u.phone,r.rule_name AS ruleName,"
                + "c.progress_snapshot AS progressSnapshot,c.reward_points AS rewardPoints,c.reward_snapshot AS rewardSnapshot,"
                + "c.status,c.create_time AS createTime FROM mall_tier_reward_claim c "
                + "JOIN mall_customer u ON u.id=c.customer_id JOIN mall_tier_reward_rule r ON r.id=c.rule_id ORDER BY c.id DESC");
    }

    public List<Map<String, Object>> adminCustomers()
    {
        return jdbc.queryForList("SELECT c.id,c.nickname,c.phone,c.points,c.status,c.create_time AS createTime, "
                + "CASE WHEN EXISTS(SELECT 1 FROM mall_customer_identity wi WHERE wi.customer_id=c.id AND wi.provider='WECHAT_MINI_PROGRAM' AND wi.app_id=?) THEN '已绑定' ELSE '未绑定' END AS wechatBinding, "
                + "(SELECT MAX(sl.create_time) FROM mall_customer_security_log sl WHERE sl.customer_id=c.id AND sl.event_type='BIND_WECHAT') AS wechatBoundAt, "
                + "CASE WHEN COALESCE(c.password_hash,'')<>'' AND EXISTS(SELECT 1 FROM mall_customer_identity i WHERE i.customer_id=c.id) THEN '手机号 / 微信' "
                + "WHEN COALESCE(c.password_hash,'')<>'' THEN '手机号密码' "
                + "WHEN EXISTS(SELECT 1 FROM mall_customer_identity i WHERE i.customer_id=c.id) THEN '微信' ELSE '未绑定' END AS loginType, "
                + "(SELECT COUNT(*) FROM mall_order o WHERE o.customer_id=c.id) AS orderCount, "
                + "(SELECT COUNT(*) FROM mall_address a WHERE a.customer_id=c.id) AS addressCount "
                + "FROM mall_customer c WHERE c.status<>'2' ORDER BY c.id DESC", wechatService.appId());
    }

    @Transactional
    public void adminUpdateProduct(Long id, Map<String, Object> body)
    {
        validateProductNumbers(body);
		requireProductAnyStatus(id);
		BigDecimal price = decimalValue(body.get("price"));
		Map<String, Object> category = resolveProductCategory(body);
		jdbc.update("UPDATE mall_product SET store_id=?,category=?,category_id=?,name=?,short_name=?,spec=?,price=?,reward_points=?,stock=?,"
				  + "image_key=?,gallery_images=?,description=?,origin=?,grade_name=?,raw_material=?,brew_guide=?,batch_no=?,"
				  + "traceability_info=?,shelf_life=?,status=? WHERE id=?", longValue(body.get("storeId"),1L),category.get("name"), category.get("id"), required(body,"name"),
				required(body,"shortName"), stringValue(body.get("spec")), price,
				rewardPoints(price), intValue(body.get("stock"),0), stringValue(body.get("imageKey")), galleryValue(body.get("galleryImages")),
				stringValue(body.get("description")), stringValue(body.get("origin")), stringValue(body.get("gradeName")),
				stringValue(body.get("rawMaterial")), stringValue(body.get("brewGuide")), stringValue(body.get("batchNo")),
				stringValue(body.get("traceabilityInfo")), stringValue(body.get("shelfLife")), stringValue(body.get("status")), id);
        syncDefaultSku(id, stringValue(body.get("spec")), price, intValue(body.get("stock"),0), stringValue(body.get("status")));
        syncProductMedia(id, body);
        saveProductCommission(id, body);
    }

	@Transactional
	public Long adminCreateProduct(Map<String, Object> body)
	{
        validateProductNumbers(body);
		BigDecimal price = decimalValue(body.get("price"));
		Map<String, Object> category = resolveProductCategory(body);
		jdbc.update("INSERT INTO mall_product(store_id,category,category_id,name,short_name,spec,price,reward_points,stock,sales,image_key,gallery_images,"
				+ "description,origin,grade_name,raw_material,brew_guide,batch_no,traceability_info,shelf_life,status) "
				+ "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
				longValue(body.get("storeId"),1L),category.get("name"),category.get("id"), required(body,"name"), required(body,"shortName"), stringValue(body.get("spec")),
				price, rewardPoints(price), intValue(body.get("stock"),0), 0,
				stringValue(body.get("imageKey")), galleryValue(body.get("galleryImages")), stringValue(body.get("description")), stringValue(body.get("origin")),
				stringValue(body.get("gradeName")), stringValue(body.get("rawMaterial")), stringValue(body.get("brewGuide")),
				stringValue(body.get("batchNo")), stringValue(body.get("traceabilityInfo")), stringValue(body.get("shelfLife")), stringValue(body.get("status")));
		Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        syncDefaultSku(id, stringValue(body.get("spec")), price, intValue(body.get("stock"),0), stringValue(body.get("status")));
        syncProductMedia(id, body);
        saveProductCommission(id, body);
        return id;
	}

    private void validateProductNumbers(Map<String, Object> body)
    {
        BigDecimal price;
        BigDecimal stock;
        try {
            price = new BigDecimal(String.valueOf(body.get("price")));
            stock = new BigDecimal(String.valueOf(body.get("stock")));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("商品价格和库存必须填写有效数字");
        }
        if (price.signum() < 0 || price.compareTo(new BigDecimal("999999.99")) > 0 || price.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("商品价格须为0至999999.99元，最多两位小数");
        if (stock.signum() < 0 || stock.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 || stock.stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("商品库存须为0至2147483647的整数");
        String state = stringValue(body.get("status"));
        if (!("0".equals(state) || "1".equals(state)))
            throw new IllegalArgumentException("商品状态只能为上架或下架");
    }

    private void saveProductCommission(Long id, Map<String,Object> body)
    {
        if (!body.containsKey("commissionAmount")) return;
        BigDecimal amount = decimalValue(body.get("commissionAmount"));
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("999999.99")) > 0 || amount.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("商品固定提成须为0至999999.99元，最多两位小数");
        jdbc.update("UPDATE mall_product SET commission_amount=? WHERE id=?", amount, id);
    }

    @Transactional
    public void adminDeleteProduct(Long id)
    {
        int ordered = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order_item WHERE product_id=?", Integer.class, id);
        if (ordered > 0) throw new IllegalArgumentException("该商品已有订单记录，不能删除，请改为下架");
        jdbc.update("DELETE FROM mall_cart WHERE product_id=?", id);
        jdbc.update("DELETE FROM mall_favorite WHERE product_id=?", id);
        jdbc.update("DELETE FROM mall_topic_product WHERE product_id=?", id);
        jdbc.update("DELETE FROM mall_product_media WHERE product_id=?", id);
        jdbc.update("DELETE FROM mall_product_sku WHERE product_id=?", id);
        jdbc.update("DELETE FROM mall_product WHERE id=?", id);
    }

    @Transactional
    public void adminUpdateOrder(Long id, Map<String, Object> body)
    {
        String requestNo = normalizedRequestNo(body.get("requestNo"), "订单后台操作");
        Map<String, Object> order = first("SELECT id,order_no,customer_id,reward_points,points_used,paid_amount,"
                + "payment_status,inventory_state,status FROM mall_order WHERE id=? FOR UPDATE", id);
        if (order == null)
        {
            throw new IllegalArgumentException("订单不存在");
        }
        Integer processed = jdbc.queryForObject("SELECT COUNT(*) FROM mall_order_operation_log WHERE order_id=? AND request_no=?", Integer.class, id, requestNo);
        if (processed != null && processed > 0) return;

        String oldStatus = String.valueOf(order.get("status"));
        String newStatus = required(body, "status");
        String carrier = stringValue(body.get("carrier"));
        String trackingNo = stringValue(body.get("trackingNo"));
        validateAdminOrderTransition(oldStatus, newStatus);
        if ("待收货".equals(newStatus) && !"待收货".equals(oldStatus))
        {
            MallOrderStateMachine.requireShippable(oldStatus, stringValue(order.get("payment_status")));
        }
        if ("待收货".equals(newStatus) && trackingNo.trim().isEmpty())
        {
            throw new IllegalArgumentException("发货前必须填写物流单号");
        }
        jdbc.update("UPDATE mall_order SET status=?,carrier=?,tracking_no=? WHERE id=?",
                newStatus, carrier, trackingNo, id);
        if (!newStatus.equals(oldStatus))
            logOrderTransition(order, "管理员", SecurityUtils.getUserId(), "管理后台", oldStatus, newStatus,
                    stringValue(body.get("adminRemark")), requestNo);

        if ("待收货".equals(newStatus) && !"待收货".equals(oldStatus))
        {
            String description = (carrier.isEmpty() ? "承运商" : carrier) + "已揽收"
                    + (trackingNo.isEmpty() ? "" : "，运单号：" + trackingNo);
            jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                      + "SELECT ?, '已发货', ?, NOW(), 10 WHERE NOT EXISTS "
                      + "(SELECT 1 FROM mall_logistics WHERE order_id=? AND status_name='已发货')",
                    id, description, id);
        }

        if ("已完成".equals(newStatus) && !"已完成".equals(oldStatus))
        {
            awardOrderPoints(order);
            jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                      + "SELECT ?, '已签收', '订单已完成，感谢您的购买', NOW(), 99 WHERE NOT EXISTS "
                      + "(SELECT 1 FROM mall_logistics WHERE order_id=? AND status_name='已签收')", id, id);
            settleCommissions(id);
        }
        if ("已取消".equals(newStatus))
        {
            releasePendingReservation(order);
            restoreOrderPoints(order);
            cancelCommissions(id);
            releaseCoupon(id, "后台取消订单");
        }
        if (!newStatus.equals(oldStatus))
        {
            notifyCustomer(longValue(order.get("customer_id"), 0L), "订单", "订单状态已更新",
                    "订单 " + order.get("order_no") + " 已更新为“" + newStatus + "”",
                    "orderDetail", "orderNo=" + order.get("order_no"), "ORDER",
                    stringValue(order.get("order_no")), "ADMIN_" + newStatus);
        }
    }

    public List<Map<String, Object>> adminOrderExpedites()
    {
        return jdbc.queryForList("SELECT e.id,e.request_no AS requestNo,e.order_id AS orderId,e.order_no AS orderNo,"
                + "e.customer_id AS customerId,c.nickname,c.phone,e.status,e.customer_remark AS customerRemark,"
                + "e.admin_remark AS adminRemark,e.create_time AS createTime,e.update_time AS updateTime "
                + "FROM mall_order_expedite e JOIN mall_customer c ON c.id=e.customer_id ORDER BY e.id DESC");
    }

    @Transactional
    public void adminUpdateOrderExpedite(Long id, Map<String, Object> body)
    {
        Map<String, Object> expedite = first("SELECT * FROM mall_order_expedite WHERE id=? FOR UPDATE", id);
        if (expedite == null) throw new IllegalArgumentException("催发货记录不存在");
        String status = required(body, "status").trim();
        if (!("待处理".equals(status) || "已处理".equals(status) || "已关闭".equals(status)))
            throw new IllegalArgumentException("催发货处理状态无效");
        jdbc.update("UPDATE mall_order_expedite SET status=?,admin_remark=? WHERE id=?", status,
                stringValue(body.get("adminRemark")), id);
        if (!status.equals(stringValue(expedite.get("status"))))
            notifyCustomer(longValue(expedite.get("customer_id"), 0L), "订单", "催发货处理进度",
                    "订单 " + expedite.get("order_no") + " 的催发货状态已更新为“" + status + "”",
                    "orderDetail", "orderNo=" + expedite.get("order_no"), "ORDER",
                    stringValue(expedite.get("order_no")), "EXPEDITE_" + status);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void adminUpdateAftersale(Long id, Map<String, Object> body)
    {
        String requestNo = normalizedRequestNo(body.get("requestNo"), "售后后台操作");
        Map<String, Object> aftersale = one("SELECT a.*,o.paid_amount,o.points_used,o.id AS orderId,o.order_no,o.reward_points," +
                "o.customer_id,o.payment_status FROM mall_aftersale a "
                + "JOIN mall_order o ON o.id=a.order_id WHERE a.id=? FOR UPDATE", id);
        Integer processed = jdbc.queryForObject("SELECT COUNT(*) FROM mall_aftersale_operation_log WHERE aftersale_id=? AND request_no=?", Integer.class, id, requestNo);
        if (processed != null && processed > 0) return;
        String oldStatus = stringValue(aftersale.get("status"));
        String status = required(body,"status").trim();
        String typeName = stringValue(aftersale.get("type_name"));
        validateAftersaleTransition(oldStatus, status, typeName);
        String adminRemark = stringValue(body.get("adminRemark")).trim();
        String returnAddress = stringValue(body.get("returnAddress")).trim();
        String exchangeCarrier = stringValue(body.get("exchangeCarrier")).trim();
        String exchangeTrackingNo = stringValue(body.get("exchangeTrackingNo")).trim();
        if ("等待用户退货".equals(status) && returnAddress.isEmpty()) throw new IllegalArgumentException("审核退货前必须填写退货地址");
        if ("换货已发出".equals(status) && (exchangeCarrier.isEmpty() || exchangeTrackingNo.isEmpty()))
            throw new IllegalArgumentException("换货发货必须填写物流公司和单号");
        jdbc.update("UPDATE mall_aftersale SET status=?,action_deadline=CASE WHEN ?='等待用户退货' THEN DATE_ADD(NOW(),INTERVAL 7 DAY) ELSE NULL END,admin_remark=?,return_address=CASE WHEN ?='' THEN return_address ELSE ? END,"
                + "exchange_carrier=CASE WHEN ?='' THEN exchange_carrier ELSE ? END,exchange_tracking_no=CASE WHEN ?='' THEN exchange_tracking_no ELSE ? END,"
                + "exchange_ship_time=CASE WHEN ?='换货已发出' THEN NOW() ELSE exchange_ship_time END,"
                + "close_reason=CASE WHEN ? IN ('审核拒绝','已关闭') THEN ? ELSE close_reason END WHERE id=?",
                status, status, adminRemark, returnAddress, returnAddress, exchangeCarrier, exchangeCarrier,
                exchangeTrackingNo, exchangeTrackingNo, status, status, adminRemark, id);
        jdbc.update("UPDATE mall_order SET aftersale_status=? WHERE id=?", status, aftersale.get("orderId"));

        Long orderId = longValue(aftersale.get("orderId"), 0L);
        if ("审核拒绝".equals(status) || "已关闭".equals(status))
        {
            releaseAftersaleQuantity(aftersale, false);
            restoreOrderAfterAftersaleClosed(orderId);
        }
        if ("退款成功".equals(status))
        {
            if ("换货".equals(typeName)) throw new IllegalArgumentException("换货售后不能执行退款");
            BigDecimal refundAmount = decimalValue(aftersale.get("requested_amount"));
            Map<String, Object> orderTotals = one("SELECT paid_amount,refunded_amount FROM mall_order WHERE id=? FOR UPDATE", orderId);
            BigDecimal remaining = decimalValue(orderTotals.get("paid_amount")).subtract(decimalValue(orderTotals.get("refunded_amount"))).max(BigDecimal.ZERO);
            refundAmount = refundAmount.min(remaining);
            if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("订单可退金额已处理完毕");
            String refundNo = stringValue(aftersale.get("refund_no"));
            if (refundNo.isEmpty()) refundNo = serial("R");
            int refunded = jdbc.update("UPDATE mall_aftersale SET refund_amount=?,refund_no=?,refund_time=NOW() WHERE id=? AND refund_time IS NULL", refundAmount, refundNo, id);
            if (refunded != 1) throw new IllegalArgumentException("该售后退款已处理，请勿重复操作");
            int orderRefunded = jdbc.update("UPDATE mall_order SET refunded_amount=refunded_amount+? WHERE id=? AND refunded_amount+?<=paid_amount", refundAmount, orderId, refundAmount);
            if (orderRefunded != 1) throw new IllegalArgumentException("累计退款金额超过订单实付金额");
            reverseOrderReward(aftersale, refundAmount, refundNo);
            // A refund-only request is valid only before shipment. Its reserved
            // inventory must be released when the simulated refund succeeds;
            // completed/received orders still require the physical-return flow.
            if ("退货退款".equals(typeName)
                    || ("仅退款".equals(typeName)
                        && "待发货".equals(stringValue(aftersale.get("source_order_status")))))
            {
                restoreAftersaleInventory(aftersale);
            }
            jdbc.update("UPDATE mall_order_item SET refunded_amount=LEAST(refundable_amount,refunded_amount+?) WHERE id=?",
                    refundAmount, aftersale.get("order_item_id"));
            releaseAftersaleQuantity(aftersale, true);
            teaFriendService.reconcileBuyer(longValue(aftersale.get("customer_id"),null));
            adjustCommissionAfterRefund(orderId, refundNo);
            Map<String, Object> totalsAfter = one("SELECT paid_amount,refunded_amount FROM mall_order WHERE id=?", orderId);
            if (decimalValue(totalsAfter.get("refunded_amount")).compareTo(decimalValue(totalsAfter.get("paid_amount"))) >= 0)
            {
                restoreOrderPoints(aftersale);
                returnUsedCoupon(orderId, "订单全额模拟退款");
            }
        }
        if ("售后完成".equals(status))
        {
            jdbc.update("UPDATE mall_aftersale SET complete_time=NOW() WHERE id=?", id);
            if ("换货".equals(typeName)) releaseAftersaleQuantity(aftersale, false);
            restoreOrderAfterAftersaleClosed(orderId);
        }
        logAftersaleTransition(id, stringValue(aftersale.get("aftersale_no")), "管理员", SecurityUtils.getUserId(),
                "管理后台", oldStatus, status, adminRemark, requestNo);
        notifyCustomer(longValue(aftersale.get("customer_id"), 0L), "订单", "售后状态已更新",
                "订单 " + aftersale.get("order_no") + " 的售后状态已更新为“" + status + "”",
                "orderDetail", "orderNo=" + aftersale.get("order_no"), "AFTERSALE",
                stringValue(aftersale.get("aftersale_no")), "ADMIN_" + status);
    }

    @Transactional
    public int closeExpiredAftersales()
    {
        List<Map<String, Object>> expired = jdbc.queryForList("SELECT * FROM mall_aftersale WHERE status='等待用户退货' "
                + "AND action_deadline IS NOT NULL AND action_deadline<NOW() ORDER BY id LIMIT 100 FOR UPDATE");
        int closed = 0;
        for (Map<String, Object> sale : expired)
        {
            int changed = jdbc.update("UPDATE mall_aftersale SET status='已关闭',close_reason='用户未在期限内填写退货物流',complete_time=NOW(),action_deadline=NULL "
                    + "WHERE id=? AND status='等待用户退货'", sale.get("id"));
            if (changed != 1) continue;
            releaseAftersaleQuantity(sale, false);
            restoreOrderAfterAftersaleClosed(longValue(sale.get("order_id"), 0L));
            String no = stringValue(sale.get("aftersale_no"));
            logAftersaleTransition(longValue(sale.get("id"), 0L), no, "系统", 0L, "售后超时任务",
                    "等待用户退货", "已关闭", "用户未在7日内填写退货物流", "aftersale-timeout-" + sale.get("id"));
            notifyCustomer(longValue(sale.get("customer_id"), 0L), "订单", "售后申请已超时关闭",
                    "售后单 " + no + " 因未在期限内填写退货物流已关闭", "aftersaleDetail",
                    "aftersaleNo=" + no, "AFTERSALE", no, "TIMEOUT_CLOSED");
            closed++;
        }
        return closed;
    }

    public void adminUpdateReward(Long id, Map<String, Object> body)
    {
        int points = intValue(body.get("points"), 0);
        int stock = intValue(body.get("stock"), 0);
        int limitQty = intValue(body.get("limitQty"), 1);
        if (points < 1 || stock < 0 || limitQty < 1) throw new IllegalArgumentException("积分价格、库存或限购数量无效");
        int updated = jdbc.update("UPDATE mall_reward SET name=?,category=?,points=?,stock=?,limit_qty=?,stock_unit=?,"
                        + "image_key=?,exchange_notes=?,delivery_method=?,status=? WHERE id=?",
                required(body,"name"), stringValue(body.get("category")), points, stock, limitQty,
                stringValue(body.get("stockUnit")), stringValue(body.get("imageKey")),
                stringValue(body.get("exchangeNotes")), stringValue(body.get("deliveryMethod")),
                stringValue(body.get("status")), id);
        if (updated == 0) throw new IllegalArgumentException("积分商品不存在");
    }

    @Transactional
    public void adminUpdateCustomer(Long id, Map<String, Object> body)
    {
        Map<String, Object> customer = one("SELECT points,phone FROM mall_customer WHERE id=? FOR UPDATE", id);
        int oldPoints = intValue(customer.get("points"), 0);
        int adjustment = intValue(body.get("pointsAdjustment"), 0);
        if (oldPoints + adjustment < 0) throw new IllegalArgumentException("积分调整后不能小于0");
        String status=stringValue(body.get("status"));
        if(!"0".equals(status)&&!"1".equals(status))throw new IllegalArgumentException("会员状态只能为正常或停用");
        String requestedPhone=stringValue(body.get("phone")).trim();
        if(!requestedPhone.equals(stringValue(customer.get("phone"))))throw new IllegalArgumentException("手机号不能通过资料编辑修改，请使用独立安全换号流程");
        jdbc.update("UPDATE mall_customer SET nickname=?,status=? WHERE id=?",
                required(body,"nickname"), status, id);
        if (adjustment != 0)
        {
            String reason = required(body, "pointsRemark");
            addPointsUnique(id, adjustment, "后台积分调整", reason, "ADMIN_ADJUST",
                    "ADMIN-" + UUID.randomUUID().toString().replace("-", ""), null);
        }
    }

    public Map<String, Object> distribution(Long customerId)
    {
        ensureDistributor(customerId);
        refreshInviteRecords(customerId);
        Map<String, Object> result = one("SELECT d.invite_code AS inviteCode,d.commission_balance AS balance,"
                + "d.pending_commission AS pending,d.frozen_commission AS frozen,d.withdrawn_commission AS withdrawn,"
                + "(SELECT COUNT(*) FROM mall_distributor c WHERE c.parent_customer_id=d.customer_id) AS directCount,"
                + "(SELECT COUNT(*) FROM mall_distributor c JOIN mall_distributor p ON p.customer_id=c.parent_customer_id WHERE p.parent_customer_id=d.customer_id) AS indirectCount "
                + "FROM mall_distributor d WHERE d.customer_id=?", customerId);
        result.put("commissions", jdbc.queryForList("SELECT mc.id,o.order_no AS orderNo,mc.level_no AS levelNo,mc.rate,mc.amount,"
                + "mc.status,mc.create_time AS createTime,c.nickname AS sourceName FROM mall_commission mc "
                + "JOIN mall_order o ON o.id=mc.order_id JOIN mall_customer c ON c.id=mc.source_customer_id "
                + "WHERE mc.beneficiary_id=? ORDER BY mc.id DESC LIMIT 50", customerId));
        result.put("downlines", jdbc.queryForList("SELECT c.id,c.nickname,CONCAT(LEFT(c.phone,3),'****',RIGHT(c.phone,4)) AS phone,"
                + "d.create_time AS bindTime,(SELECT COUNT(*) FROM mall_order o WHERE o.customer_id=c.id) AS orderCount "
                + "FROM mall_distributor d JOIN mall_customer c ON c.id=d.customer_id WHERE d.parent_customer_id=? ORDER BY d.id DESC", customerId));
        result.put("withdrawals", jdbc.queryForList("SELECT id,withdrawal_no AS withdrawalNo,amount,account_type AS accountType,"
                + "CONCAT(LEFT(account_no,3),'****',RIGHT(account_no,4)) AS accountNo,status,admin_remark AS adminRemark,create_time AS createTime "
                + "FROM mall_withdrawal WHERE customer_id=? ORDER BY id DESC LIMIT 50", customerId));
        Map<String, Object> scene = inviteSceneSummary(customerId);
        result.put("sceneCode", scene.get("sceneCode"));
        result.put("inviteUrl", scene.get("inviteUrl"));
        result.put("gift", inviteGift(customerId));
        return result;
    }

    public List<Map<String, Object>> teaFriends(Long customerId, Integer requestedLevel)
    {
        refreshInviteRecords(customerId);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (requestedLevel == null || requestedLevel.intValue() == 0 || requestedLevel.intValue() == 1)
        {
            rows.addAll(friendRows(customerId, 1));
        }
        if (requestedLevel == null || requestedLevel.intValue() == 0 || requestedLevel.intValue() == 2)
        {
            rows.addAll(friendRows(customerId, 2));
        }
        return rows;
    }

    private List<Map<String, Object>> friendRows(Long customerId, int level)
    {
        String relation = level == 1
                ? "JOIN mall_distributor d ON d.customer_id=c.id"
                : "JOIN mall_distributor d ON d.customer_id=c.id JOIN mall_distributor p ON p.customer_id=d.parent_customer_id";
        String ownerCondition = level == 1 ? "d.parent_customer_id=?" : "p.parent_customer_id=?";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.id,c.nickname,c.phone,c.create_time AS registerTime," + level + " AS levelNo,"
                + "COALESCE(SUM(CASE WHEN o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款') THEN o.paid_amount ELSE 0 END),0) AS consumption,"
                + "COUNT(CASE WHEN o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款') THEN 1 END) AS orderCount "
                + "FROM mall_customer c " + relation + " LEFT JOIN mall_order o ON o.customer_id=c.id WHERE " + ownerCondition
                + " GROUP BY c.id,c.nickname,c.phone,c.create_time ORDER BY c.id DESC", customerId);
        for (Map<String, Object> row : rows)
        {
            row.put("nickname", maskName(stringValue(row.get("nickname"))));
            row.put("phone", maskPhone(stringValue(row.get("phone"))));
        }
        return rows;
    }

    public Map<String, Object> teaFriendDetail(Long customerId, Long friendId)
    {
        int level = requireFriendLevel(customerId, friendId);
        Map<String, Object> result = one("SELECT c.id,c.nickname,c.phone,c.create_time AS registerTime,"
                + "COALESCE(SUM(CASE WHEN o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款') THEN o.paid_amount ELSE 0 END),0) AS consumption,"
                + "COUNT(CASE WHEN o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款') THEN 1 END) AS orderCount "
                + "FROM mall_customer c LEFT JOIN mall_order o ON o.customer_id=c.id WHERE c.id=? GROUP BY c.id,c.nickname,c.phone,c.create_time", friendId);
        BigDecimal commission = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM mall_commission WHERE beneficiary_id=? AND source_customer_id=?",
                BigDecimal.class, customerId, friendId);
        result.put("nickname", maskName(stringValue(result.get("nickname"))));
        result.put("phone", maskPhone(stringValue(result.get("phone"))));
        result.put("levelNo", level);
        result.put("commission", commission);
        result.put("recentOrders", teaFriendOrders(customerId, friendId));
        return result;
    }

    public List<Map<String, Object>> teaFriendOrders(Long customerId, Long friendId)
    {
        requireFriendLevel(customerId, friendId);
        return jdbc.queryForList("SELECT o.id,o.order_no AS orderNo,o.status,o.payment_status AS paymentStatus,o.paid_amount AS amount,"
                + "o.create_time AS createTime,GROUP_CONCAT(CONCAT(oi.product_name,' ×',oi.qty) ORDER BY oi.id SEPARATOR '、') AS productName,"
                + "MIN(p.image_key) AS imageKey FROM mall_order o JOIN mall_order_item oi ON oi.order_id=o.id "
                + "LEFT JOIN mall_product p ON p.id=oi.product_id WHERE o.customer_id=? AND o.payment_status='已支付' "
                + "AND o.status NOT IN ('已取消') GROUP BY o.id,o.order_no,o.status,o.payment_status,o.paid_amount,o.create_time ORDER BY o.id DESC", friendId);
    }

    public List<Map<String, Object>> inviteRecords(Long customerId)
    {
        refreshInviteRecords(customerId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.id,CASE WHEN r.score_active=1 THEN '已完成' ELSE '待下单' END AS status,r.score_active AS qualified,r.score_amount AS inviteScore,r.source,r.create_time AS registerTime,IF(r.score_active=1,r.completed_time,NULL) AS completedTime,"
                + "c.id AS customerId,c.nickname,c.phone,s.scene_code AS sceneCode,"
                + "(SELECT COUNT(*) FROM mall_order o WHERE o.customer_id=c.id AND o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款')) AS orderCount "
                + "FROM mall_invite_record r JOIN mall_customer c ON c.id=r.invitee_customer_id "
                + "LEFT JOIN mall_invite_scene s ON s.id=r.scene_id WHERE r.inviter_customer_id=? ORDER BY r.id DESC", customerId);
        for (Map<String, Object> row : rows)
        {
            row.put("nickname", maskName(stringValue(row.get("nickname"))));
            row.put("phone", maskPhone(stringValue(row.get("phone"))));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> inviteScene(Long customerId)
    {
        wechatBindingService.requireBound(customerId);
        ensureDistributor(customerId);
        Map<String, Object> existing = first("SELECT id,scene_code AS sceneCode,channel,expires_at AS expiresAt,use_count AS useCount "
                + "FROM mall_invite_scene WHERE inviter_customer_id=? AND channel='GENERAL' AND status='0' "
                + "AND (expires_at IS NULL OR expires_at>NOW()) ORDER BY id DESC LIMIT 1", customerId);
        if (existing == null)
        {
            String sceneCode = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
            jdbc.update("INSERT INTO mall_invite_scene(inviter_customer_id,scene_code,channel,status) VALUES(?,?,'GENERAL','0')", customerId, sceneCode);
            existing = one("SELECT id,scene_code AS sceneCode,channel,expires_at AS expiresAt,use_count AS useCount FROM mall_invite_scene WHERE scene_code=?", sceneCode);
        }
        existing.put("inviteUrl", "/#/pages/login/login?scene=" + existing.get("sceneCode"));
        existing.put("miniProgramCodeAvailable", wechatService.configured());
        existing.put("miniProgramCodeUrl", "/distribution/invite-scene/mini-code");
        return existing;
    }

    @Transactional
    public byte[] inviteMiniProgramCode(Long customerId)
    {
        wechatBindingService.requireBound(customerId);
        Map<String, Object> scene = first("SELECT id,scene_code,mini_code_content,mini_code_env_version FROM mall_invite_scene "
                + "WHERE inviter_customer_id=? AND channel='GENERAL' AND status='0' "
                + "AND (expires_at IS NULL OR expires_at>NOW()) ORDER BY id DESC LIMIT 1 FOR UPDATE", customerId);
        if (scene == null)
        {
            inviteScene(customerId);
            scene = one("SELECT id,scene_code,mini_code_content,mini_code_env_version FROM mall_invite_scene WHERE inviter_customer_id=? "
                    + "AND channel='GENERAL' AND status='0' ORDER BY id DESC LIMIT 1 FOR UPDATE", customerId);
        }
        Object cached = scene.get("mini_code_content");
        if (cached instanceof byte[] && ((byte[]) cached).length > 0
                && wechatService.codeEnvVersion().equals(scene.get("mini_code_env_version"))) return (byte[]) cached;
        byte[] code = wechatService.createUnlimitedMiniProgramCode(stringValue(scene.get("scene_code")));
        jdbc.update("UPDATE mall_invite_scene SET mini_code_content=?,mini_code_generated_at=NOW(),mini_code_env_version=?,"
                + "mini_code_generation_count=mini_code_generation_count+1 WHERE id=?", code, wechatService.codeEnvVersion(), scene.get("id"));
        return code;
    }

    public Map<String,Object> inviteSceneSummary(Long customerId)
    {
        if (wechatBindingService.isBound(customerId)) return inviteScene(customerId);
        Map<String,Object> summary = new LinkedHashMap<String,Object>();
        summary.put("wechatBindingRequired", true);
        summary.put("miniProgramCodeAvailable", false);
        return summary;
    }

    public Map<String, Object> inviteGift(Long customerId)
    {
        refreshInviteRecords(customerId);
        Map<String, Object> rule = first("SELECT id,rule_name AS ruleName,required_completed_invites AS requiredCount,reward_points AS rewardPoints,"
                + "gift_value AS giftValue,gift_contents AS giftContents,status,start_time AS startTime,end_time AS endTime "
                + "FROM mall_invite_gift_rule WHERE status='0' AND (start_time IS NULL OR start_time<=NOW()) "
                + "AND (end_time IS NULL OR end_time>=NOW()) ORDER BY id LIMIT 1");
        if (rule == null) throw new IllegalArgumentException("当前没有可领取的邀请礼包");
        int completed = jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=? AND status='已完成'", Integer.class, customerId);
        Map<String, Object> claim = first("SELECT claim_no AS claimNo,status,claim_time AS claimTime,reward_points AS rewardPoints "
                + "FROM mall_invite_gift_claim WHERE customer_id=? AND rule_id=?", customerId, rule.get("id"));
        rule.put("completedCount", completed);
        rule.put("eligible", claim == null && completed >= intValue(rule.get("requiredCount"), 0));
        rule.put("claimed", claim != null);
        rule.put("claim", claim);
        return rule;
    }

    @Transactional
    public Map<String, Object> claimInviteGift(Long customerId, Map<String, Object> body)
    {
        throw new IllegalArgumentException("邀请奖励已改为茶友邀请分，请进入茶友兑换页面");
    }

    public List<Map<String, Object>> adminInviteRecords()
    {
        refreshAllInviteRecords();
        return jdbc.queryForList("SELECT r.id,CASE WHEN r.score_active=1 THEN '已完成' ELSE '待下单' END AS status,r.score_amount AS inviteScore,r.source,r.create_time AS createTime,IF(r.score_active=1,r.completed_time,NULL) AS completedTime,"
                + "i.id AS inviterId,i.nickname AS inviterName,i.phone AS inviterPhone,e.id AS inviteeId,e.nickname AS inviteeName,e.phone AS inviteePhone,"
                + "s.scene_code AS sceneCode,o.order_no AS completedOrderNo FROM mall_invite_record r "
                + "JOIN mall_customer i ON i.id=r.inviter_customer_id JOIN mall_customer e ON e.id=r.invitee_customer_id "
                + "LEFT JOIN mall_invite_scene s ON s.id=r.scene_id LEFT JOIN mall_order o ON o.id=r.completed_order_id ORDER BY r.id DESC");
    }

    public List<Map<String, Object>> adminInviteScenes()
    {
        return jdbc.queryForList("SELECT s.id,s.scene_code AS sceneCode,s.channel,s.status,s.expires_at AS expiresAt,s.use_count AS useCount,"
                + "s.create_time AS createTime,c.id AS inviterId,c.nickname AS inviterName,c.phone AS inviterPhone,"
                + "(SELECT COUNT(*) FROM mall_invite_record r WHERE r.scene_id=s.id) AS bindCount "
                + "FROM mall_invite_scene s JOIN mall_customer c ON c.id=s.inviter_customer_id ORDER BY s.id DESC");
    }

    public List<Map<String, Object>> adminInviteGiftRules()
    {
        return jdbc.queryForList("SELECT id,rule_name AS ruleName,required_completed_invites AS requiredCount,reward_type AS rewardType,reward_id AS rewardId,reward_qty AS rewardQty,reward_points AS rewardPoints,stock,"
                + "gift_value AS giftValue,gift_contents AS giftContents,status,sort_no AS sortNo,start_time AS startTime,end_time AS endTime,update_time AS updateTime "
                + "FROM mall_invite_gift_rule ORDER BY sort_no,required_completed_invites,id");
    }

    public Map<String,Object> adminInviteRuleConfig()
    {
        return one("SELECT id,valid_condition AS validCondition,description,status,version_no AS versionNo,update_time AS updateTime "
                + "FROM mall_invite_rule_config WHERE id=1");
    }

    @Transactional
    public void adminUpdateInviteRuleConfig(Long id,Map<String,Object> body)
    {
        if(id==null||id.longValue()!=1L)throw new IllegalArgumentException("邀请有效条件配置不存在");
        String condition=required(body,"validCondition");
        if(!"FIRST_COMPLETED_ORDER".equals(condition))throw new IllegalArgumentException("当前版本仅支持好友首笔有效订单完成后计入");
        String status=required(body,"status");
        if(!("0".equals(status)||"1".equals(status)))throw new IllegalArgumentException("配置状态无效");
        jdbc.update("UPDATE mall_invite_rule_config SET valid_condition=?,description=?,status=?,version_no=version_no+1,updated_by=? WHERE id=1",
                condition,stringValue(body.get("description")),status,SecurityUtils.getUserId());
    }

    @Transactional
    public void adminUpdateInviteGiftRule(Long id, Map<String, Object> body)
    {
        int requiredCount = intValue(body.get("requiredCount"), 0);
        int rewardPoints = intValue(body.get("rewardPoints"), 0);
        if (requiredCount < 1 || requiredCount > 100) throw new IllegalArgumentException("达标邀请人数必须在1到100之间");
        if (rewardPoints < 0 || rewardPoints > 100000) throw new IllegalArgumentException("奖励积分范围无效");
        String rewardType=stringValue(body.get("rewardType")); if(rewardType.isEmpty())rewardType="POINTS";
        if(!("POINTS".equals(rewardType)||"REWARD".equals(rewardType)))throw new IllegalArgumentException("奖励类型无效");
        int rewardQty=Math.max(1,intValue(body.get("rewardQty"),1)); int stock=Math.max(0,intValue(body.get("stock"),0));
        jdbc.update("UPDATE mall_invite_gift_rule SET rule_name=?,required_completed_invites=?,reward_type=?,reward_id=?,reward_qty=?,reward_points=?,stock=?,gift_value=?,gift_contents=?,status=?,sort_no=?,start_time=?,end_time=? WHERE id=?",
                required(body,"ruleName"), requiredCount,rewardType,longValue(body.get("rewardId"),null),rewardQty,rewardPoints,stock,decimalValue(body.get("giftValue")),required(body,"giftContents"),
                required(body,"status"),intValue(body.get("sortNo"),0),emptyToNull(body.get("startTime")),emptyToNull(body.get("endTime")),id);
    }

    @Transactional
    public Long adminCreateInviteGiftRule(Map<String,Object> body)
    {
        jdbc.update("INSERT INTO mall_invite_gift_rule(rule_name,required_completed_invites,reward_type,reward_qty,reward_points,stock,gift_value,gift_contents,status,sort_no) VALUES('新邀请阶梯',1,'POINTS',1,0,0,0,'待配置','1',0)");
        Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        adminUpdateInviteGiftRule(id,body);
        return id;
    }

    public List<Map<String, Object>> adminInviteGiftClaims()
    {
        return jdbc.queryForList("SELECT g.id,g.claim_no AS claimNo,g.status,g.completed_invite_count AS completedCount,g.reward_points AS rewardPoints,"
                + "g.reward_snapshot AS rewardSnapshot,g.admin_remark AS adminRemark,g.claim_time AS claimTime,c.id AS customerId,c.nickname,c.phone,"
                + "r.rule_name AS ruleName FROM mall_invite_gift_claim g JOIN mall_customer c ON c.id=g.customer_id "
                + "JOIN mall_invite_gift_rule r ON r.id=g.rule_id ORDER BY g.id DESC");
    }

    public void adminUpdateInviteGiftClaim(Long id, Map<String, Object> body)
    {
        String status = required(body,"status");
        if (!("已领取".equals(status) || "处理中".equals(status) || "已发放".equals(status) || "异常".equals(status)))
            throw new IllegalArgumentException("不支持的礼包处理状态");
        jdbc.update("UPDATE mall_invite_gift_claim SET status=?,admin_remark=? WHERE id=?", status, stringValue(body.get("adminRemark")), id);
    }

    private int requireFriendLevel(Long customerId, Long friendId)
    {
        if (customerId.equals(friendId)) throw new IllegalArgumentException("不能查询自己的茶友明细");
        int direct = jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor WHERE customer_id=? AND parent_customer_id=?", Integer.class, friendId, customerId);
        if (direct > 0) return 1;
        int indirect = jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor d JOIN mall_distributor p ON p.customer_id=d.parent_customer_id "
                + "WHERE d.customer_id=? AND p.parent_customer_id=?", Integer.class, friendId, customerId);
        if (indirect > 0) return 2;
        throw new IllegalArgumentException("无权查看该茶友数据");
    }

    private void refreshInviteRecords(Long customerId)
    {
        teaFriendService.reconcile(customerId);
    }

    private void refreshAllInviteRecords()
    {
        for(Long owner:jdbc.queryForList("SELECT DISTINCT r.inviter_customer_id FROM mall_invite_record r JOIN mall_customer c ON c.id=r.inviter_customer_id AND c.status='0'",Long.class))teaFriendService.reconcile(owner);
    }

    private String maskPhone(String phone)
    {
        if (phone == null || phone.length() < 7) return phone == null || phone.isEmpty() ? "未绑定手机" : "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskName(String name)
    {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return "茶友";
        return value.substring(0, 1) + (value.length() > 1 ? "**" : "茶友");
    }

    public Map<String, Object> requestWithdrawal(Long customerId, Map<String, Object> body)
    {
        // Keep legacy clients on the same configured, idempotent and audited withdrawal path.
        return accountCouponService.requestWithdrawal(customerId, body);
    }

    public List<Map<String, Object>> adminDistributors()
    {
        return jdbc.queryForList("SELECT d.id,d.customer_id AS customerId,c.nickname,c.phone,d.invite_code AS inviteCode,"
                + "p.nickname AS parentName,d.commission_balance AS balance,d.pending_commission AS pending,"
                + "d.frozen_commission AS frozen,d.withdrawn_commission AS withdrawn,d.status,d.create_time AS createTime "
                + "FROM mall_distributor d JOIN mall_customer c ON c.id=d.customer_id "
                + "LEFT JOIN mall_customer p ON p.id=d.parent_customer_id ORDER BY d.id DESC");
    }

    public List<Map<String, Object>> adminWithdrawals()
    {
        return jdbc.queryForList("SELECT w.id,w.withdrawal_no AS withdrawalNo,c.nickname,c.phone,w.amount,"
                + "w.account_type AS accountType,w.account_no AS accountNo,w.status,w.admin_remark AS adminRemark,"
                + "w.create_time AS createTime,w.review_time AS reviewTime FROM mall_withdrawal w "
                + "JOIN mall_customer c ON c.id=w.customer_id ORDER BY w.id DESC");
    }

    public List<Map<String, Object>> adminServiceTickets()
    {
        return jdbc.queryForList("SELECT t.id,t.ticket_no AS ticketNo,c.nickname,c.phone,t.category,t.content,t.contact,"
                + "t.status,t.reply,t.create_time AS createTime,t.update_time AS updateTime FROM mall_service_ticket t "
                + "JOIN mall_customer c ON c.id=t.customer_id ORDER BY t.id DESC");
    }

    public Map<String, Object> adminServiceTicketDetail(Long id)
    {
        Map<String, Object> ticket = one("SELECT t.id,t.ticket_no AS ticketNo,t.customer_id AS customerId,c.nickname,c.phone,t.category,t.content,t.contact," 
                + "t.status,t.reply,t.reply_time AS replyTime,t.create_time AS createTime,t.update_time AS updateTime "
                + "FROM mall_service_ticket t JOIN mall_customer c ON c.id=t.customer_id WHERE t.id=?", id);
        ticket.put("messages", jdbc.queryForList("SELECT id,sender_type AS senderType,sender_id AS senderId,content,attachment_url AS attachmentUrl," 
                + "from_status AS fromStatus,to_status AS toStatus,create_time AS createTime "
                + "FROM mall_service_ticket_message WHERE ticket_id=? ORDER BY create_time,id", id));
        return ticket;
    }

    @Transactional
    public void adminUpdateServiceTicket(Long id, Map<String, Object> body)
    {
        String requestNo = normalizedRequestNo(body.get("requestNo"), "客服后台操作");
        String status = required(body, "status");
        if (!("待处理".equals(status) || "处理中".equals(status) || "已回复".equals(status) || "已完成".equals(status)))
            throw new IllegalArgumentException("不支持的工单状态");
        String reply = stringValue(body.get("reply")).trim();
        if (("已回复".equals(status) || "已完成".equals(status)) && reply.length() < 2)
            throw new IllegalArgumentException("回复或完成工单前请填写客服回复");
        Map<String, Object> ticket = one("SELECT ticket_no,customer_id,status FROM mall_service_ticket WHERE id=? FOR UPDATE", id);
        Integer processed = jdbc.queryForObject("SELECT COUNT(*) FROM mall_service_ticket_message WHERE ticket_id=? AND request_no=?", Integer.class, id, requestNo);
        if (processed != null && processed > 0) return;
        String fromStatus = stringValue(ticket.get("status"));
        if (!validTicketTransition(fromStatus, status)) throw new IllegalArgumentException("工单状态不能从" + fromStatus + "变更为" + status);
        int changed = jdbc.update("UPDATE mall_service_ticket SET status=?,reply=?,reply_time=CASE WHEN ?<>'' THEN NOW() ELSE reply_time END,handler_id=? WHERE id=? AND status=?",
                status, reply, reply, SecurityUtils.getUserId(), id, fromStatus);
        if (changed != 1) throw new IllegalArgumentException("工单状态已变化，请刷新后重试");
        jdbc.update("INSERT INTO mall_service_ticket_message(ticket_id,sender_type,sender_id,content,attachment_url,from_status,to_status,request_no) "
                  + "VALUES(?,'客服',?,?,?, ?,?,?)", id, SecurityUtils.getUserId(), reply.isEmpty() ? ("状态变更为" + status) : reply,
                safeUploadUrl(body.get("attachmentUrl")), fromStatus, status,
                requestNo);
        notifyCustomer(longValue(ticket.get("customer_id"), 0L), "客服", "客服工单状态已更新",
                "工单 " + ticket.get("ticket_no") + " 已更新为“" + status + "”",
                "serviceTicketDetail", "ticketNo=" + ticket.get("ticket_no"), "SERVICE_TICKET",
                stringValue(ticket.get("ticket_no")), "ADMIN_" + status);
    }

    public List<Map<String, Object>> adminExchanges()
    {
        return jdbc.queryForList("SELECT e.id,e.exchange_no AS exchangeNo,c.nickname,c.phone,r.name,e.qty,e.points_cost AS pointsCost,"
                + "e.status,e.receiver_name AS receiverName,e.receiver_phone AS receiverPhone,e.receiver_address AS receiverAddress,"
                + "e.carrier,e.tracking_no AS trackingNo,e.received_time AS receivedTime,e.create_time AS createTime,e.update_time AS updateTime FROM mall_exchange e "
                + "JOIN mall_customer c ON c.id=e.customer_id JOIN mall_reward r ON r.id=e.reward_id WHERE e.score_currency='POINTS' ORDER BY e.id DESC");
    }

    @Transactional
    public void adminUpdateExchange(Long id, Map<String, Object> body)
    {
        Long customerId = longValue(one("SELECT customer_id FROM mall_exchange WHERE id=?",id).get("customer_id"), null);
        one("SELECT id,points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        Map<String, Object> exchange = one("SELECT * FROM mall_exchange WHERE id=? FOR UPDATE", id);
        String oldStatus = stringValue(exchange.get("status"));
        String status = required(body, "status");
        if("INVITE".equals(exchange.get("score_currency"))) {
            if("已取消".equals(status)){teaFriendService.cancel(customerId,stringValue(exchange.get("exchange_no")));return;}
            if(!"APPROVED".equals(exchange.get("review_status")))throw new IllegalArgumentException("茶友兑换需先通过审核，才能发货");
        }
        boolean allowed = oldStatus.equals(status)
                || ("待处理".equals(oldStatus) && ("待发货".equals(status) || "已取消".equals(status)))
                || ("待发货".equals(oldStatus) && ("配送中".equals(status) || "已取消".equals(status)));
        if (!allowed) throw new IllegalArgumentException("后台只能将兑换单发货至配送中，已完成必须由用户确认收货");
        String carrier = stringValue(body.get("carrier")).trim();
        String trackingNo = stringValue(body.get("trackingNo")).trim();
        if ("配送中".equals(status) && trackingNo.isEmpty()) throw new IllegalArgumentException("发货前必须填写物流单号");
        if ("已取消".equals(status) && !"已取消".equals(oldStatus))
        {
            jdbc.update("UPDATE mall_reward SET stock=stock+? WHERE id=?", exchange.get("qty"), exchange.get("reward_id"));
            boolean restored = addPointsUnique(customerId, intValue(exchange.get("points_cost"), 0), "兑换取消退回",
                    "兑换单号：" + stringValue(exchange.get("exchange_no")), "EXCHANGE_REFUND",
                    stringValue(exchange.get("exchange_no")), null);
            if (!restored) throw new IllegalStateException("兑换退回流水已存在但兑换状态未取消");
        }
        jdbc.update("UPDATE mall_exchange SET status=?,carrier=?,tracking_no=? WHERE id=?", status, carrier, trackingNo, id);
        if("INVITE".equals(exchange.get("score_currency"))&&!oldStatus.equals(status))
            notifyCustomer(customerId,"活动","茶友兑换已发货","兑换单 "+exchange.get("exchange_no")+" 已发货："+carrier+" "+trackingNo,"/pages/invite-rewards/invite-rewards","tab=records","TEA_FRIEND",stringValue(exchange.get("exchange_no")),"SHIPPED");
    }

    public List<Map<String, Object>> adminReviews()
    {
        return jdbc.queryForList("SELECT r.id,o.order_no AS orderNo,p.name AS productName,c.nickname,c.phone,r.rating,r.content,"
                + "r.status,r.create_time AS createTime FROM mall_review r JOIN mall_order o ON o.id=r.order_id "
                + "JOIN mall_product p ON p.id=r.product_id JOIN mall_customer c ON c.id=r.customer_id ORDER BY r.id DESC");
    }

    @Transactional
    public void adminUpdateWithdrawal(Long id, Map<String, Object> body)
    {
        // Both historical and current admin URLs must use the same locked, audited state machine.
        accountCouponService.reviewWithdrawal(id, body);
    }

    private void validateAdminOrderTransition(String oldStatus, String newStatus)
    {
        MallOrderStateMachine.requireAdminTransition(oldStatus, newStatus);
    }

    private void validateAftersaleTransition(String oldStatus, String newStatus, String typeName)
    {
        MallAftersaleStateMachine.requireAdminTransition(oldStatus, newStatus, typeName);
    }

    private void restoreOrderPoints(Map<String, Object> order)
    {
        Object usedValue = order.containsKey("points_used") ? order.get("points_used") : order.get("pointsUsed");
        int pointsUsed = intValue(usedValue, 0);
        if (pointsUsed <= 0) return;
        Long customerId = ((Number) (order.containsKey("customer_id") ? order.get("customer_id") : order.get("customerId"))).longValue();
        String orderNo = stringValue(order.containsKey("order_no") ? order.get("order_no") : order.get("orderNo"));
        int deducted = jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_log WHERE customer_id=? AND "
                + "((business_type='ORDER_POINTS_USE' AND business_key=?) OR "
                + "(title='订单积分抵扣' AND description=?))", Integer.class, customerId, orderNo, "订单号：" + orderNo);
        if (deducted == 0) return;
        int restored = jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_log WHERE customer_id=? AND title='订单积分退回' AND description=?",
                Integer.class, customerId, "订单号：" + orderNo);
        if (restored == 0)
        {
            addPointsUnique(customerId, pointsUsed, "订单积分退回", "订单号：" + orderNo,
                    "ORDER_POINTS_RETURN", orderNo, ((Number) (order.containsKey("orderId") ? order.get("orderId") : order.get("id"))).longValue());
        }
    }

    private void restoreOrderInventory(Map<String, Object> order)
    {
        Long orderId = ((Number) (order.containsKey("orderId") ? order.get("orderId") : order.get("id"))).longValue();
        int restored = jdbc.queryForObject("SELECT COUNT(*) FROM mall_logistics WHERE order_id=? AND status_name='库存已退回'",
                Integer.class, orderId);
        if (restored > 0) return;
        List<Map<String, Object>> items = jdbc.queryForList("SELECT product_id,sku_id,qty FROM mall_order_item WHERE order_id=?", orderId);
        for (Map<String, Object> item : items)
        {
            jdbc.update("UPDATE mall_product SET stock=stock+?,sales=GREATEST(0,sales-?) WHERE id=?",
                    item.get("qty"), item.get("qty"), item.get("product_id"));
            Long skuId = longValue(item.get("sku_id"), null);
            if (skuId != null)
                jdbc.update("UPDATE mall_product_sku SET stock=stock+?,sales=GREATEST(0,sales-?) WHERE id=?",
                        item.get("qty"), item.get("qty"), skuId);
        }
        jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                + "VALUES(?,'库存已退回','订单取消或退货完成，商品库存已自动回补',NOW(),100)", orderId);
    }

    private void releasePendingReservation(Map<String, Object> order)
    {
        Long orderId = longValue(order.get("id"), null);
        if (orderId == null || !"RESERVED".equals(stringValue(order.get("inventory_state")))) return;
        int changed = jdbc.update("UPDATE mall_order SET inventory_state='RELEASED',reserved_until=NULL "
                + "WHERE id=? AND inventory_state='RESERVED'", orderId);
        if (changed != 1) return;
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT product_id,sku_id,qty FROM mall_order_item WHERE order_id=?", orderId);
        for (Map<String, Object> item : items)
        {
            int qty = intValue(item.get("qty"), 0);
            jdbc.update("UPDATE mall_product SET stock=stock+? WHERE id=?", qty, item.get("product_id"));
            Long skuId = longValue(item.get("sku_id"), null);
            if (skuId != null) jdbc.update("UPDATE mall_product_sku SET stock=stock+? WHERE id=?", qty, skuId);
        }
        jdbc.update("INSERT INTO mall_logistics(order_id,status_name,description,event_time,sort_no) "
                + "VALUES(?,'预占库存已释放','待付款订单取消或超时关闭，库存已自动释放',NOW(),98)", orderId);
    }

    @Transactional
    public int closeExpiredOrders()
    {
        List<Long> ids = jdbc.queryForList("SELECT id FROM mall_order WHERE status='待付款' "
                + "AND inventory_state='RESERVED' AND reserved_until IS NOT NULL AND reserved_until<=NOW() ORDER BY id LIMIT 100",
                Long.class);
        int closed = 0;
        for (Long id : ids)
        {
            Map<String, Object> order = first("SELECT * FROM mall_order WHERE id=? FOR UPDATE", id);
            if (order == null || !"待付款".equals(stringValue(order.get("status")))
                    || !"RESERVED".equals(stringValue(order.get("inventory_state")))) continue;
            int changed = jdbc.update("UPDATE mall_order SET status='已关闭',cancel_reason='待付款超时',cancel_time=NOW() "
                    + "WHERE id=? AND status='待付款'", id);
            if (changed != 1) continue;
            releasePendingReservation(order);
            releaseCoupon(id, "待付款订单超时关闭");
            logOrderTransition(order, "系统", 0L, "超时关闭任务", "待付款", "已关闭", "超过30分钟未支付", "timeout_" + id);
            notifyCustomer(longValue(order.get("customer_id"), 0L), "订单", "订单已关闭",
                    "订单 " + order.get("order_no") + " 因超时未支付已关闭", "orderDetail",
                    "orderNo=" + order.get("order_no"), "ORDER", stringValue(order.get("order_no")), "TIMEOUT_CLOSED");
            closed++;
        }
        return closed;
    }

    private void ensureDistributor(Long customerId)
    {
        String inviteCode = "TEA" + Long.toString(customerId, 36).toUpperCase();
        jdbc.update("INSERT IGNORE INTO mall_distributor(customer_id,invite_code,status) VALUES(?,?,'0')", customerId, inviteCode);
    }

    private void createPendingCommissions(Long orderId, Long sourceCustomerId, BigDecimal paidAmount)
    {
        Map<String, Object> source = first("SELECT parent_customer_id FROM mall_distributor WHERE customer_id=?", sourceCustomerId);
        if (source == null || source.get("parent_customer_id") == null) return;
        Long parentId = ((Number) source.get("parent_customer_id")).longValue();
        if (parentId.equals(sourceCustomerId) || jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor WHERE customer_id=? AND partner_status='审核通过' AND status='0'", Integer.class, parentId) != 1) return;
        BigDecimal amount = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM (SELECT product_id,MAX(commission_amount) AS amount FROM mall_order_item WHERE order_id=? GROUP BY product_id) fixed_items", BigDecimal.class, orderId);
        addPendingCommission(orderId, sourceCustomerId, parentId, 1, amount, paidAmount, null);
    }

    private void addPendingCommission(Long orderId, Long sourceCustomerId, Long beneficiaryId, int level,
            BigDecimal amount, BigDecimal paidAmount, Long ruleId)
    {
        BigDecimal rate = BigDecimal.ZERO; // Fixed amount, not a percentage.
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;
        ensureDistributor(beneficiaryId);
        Map<String,Object> before=commissionAccount(beneficiaryId);
        String commissionNo="CM"+orderId+"-"+beneficiaryId+"-"+level;
        int inserted=jdbc.update("INSERT IGNORE INTO mall_commission(commission_no,order_id,beneficiary_id,source_customer_id,rule_id,base_amount,level_no,rate,amount,status,settlement_status) "
                + "VALUES(?,?,?,?,?,?,?,?,?,'待结算','待结算')", commissionNo, orderId, beneficiaryId, sourceCustomerId, ruleId, paidAmount, level, rate, amount);
        if(inserted==0)return;
        Long commissionId=jdbc.queryForObject("SELECT id FROM mall_commission WHERE order_id=? AND beneficiary_id=? AND level_no=?",Long.class,orderId,beneficiaryId,level);
        jdbc.update("UPDATE mall_distributor SET pending_commission=pending_commission+? WHERE customer_id=?", amount, beneficiaryId);
        addCommissionLedger(commissionId,beneficiaryId,"佣金待结算",amount,"ORDER_PENDING-"+orderId+"-"+level,"订单佣金进入待结算",before);
    }

    private void settleCommissions(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,beneficiary_id,amount,level_no FROM mall_commission WHERE order_id=? AND status='待结算' FOR UPDATE", orderId);
        for (Map<String, Object> row : rows)
        {
            Map<String,Object> before=commissionAccount(longValue(row.get("beneficiary_id"),0L));
            jdbc.update("UPDATE mall_distributor SET pending_commission=pending_commission-?,commission_balance=commission_balance+? WHERE customer_id=?",
                    row.get("amount"), row.get("amount"), row.get("beneficiary_id"));
            addCommissionLedger(longValue(row.get("id"),null),longValue(row.get("beneficiary_id"),0L),"佣金结算",decimalValue(row.get("amount")),
                    "ORDER_SETTLE-"+orderId+"-"+row.get("level_no"),"订单完成，佣金转为可提现",before);
        }
        jdbc.update("UPDATE mall_commission SET status='已结算',settlement_status='可提现',settle_time=NOW() WHERE order_id=? AND status='待结算'", orderId);
    }

    private void adjustCommissionAfterRefund(Long orderId,String refundNo)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM mall_commission WHERE order_id=? AND status IN ('待结算','已结算') FOR UPDATE",orderId);
        if(rows.isEmpty())return;
        if(rows.stream().anyMatch(r->decimalValue(r.get("rate")).signum()!=0)){cancelCommissions(orderId);return;} // Preserve historical percentage-order handling.
        Map<String,Object> order=one("SELECT paid_amount,refunded_amount FROM mall_order WHERE id=?",orderId);
        BigDecimal remaining=decimalValue(order.get("refunded_amount")).compareTo(decimalValue(order.get("paid_amount")))>=0?BigDecimal.ZERO:
            jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM (SELECT product_id,MAX(commission_amount) amount FROM mall_order_item WHERE order_id=? GROUP BY product_id HAVING SUM(qty)>SUM(refunded_qty)) kept",BigDecimal.class,orderId);
        for(Map<String,Object> row:rows){
            BigDecimal current=decimalValue(row.get("amount")),target=current.min(remaining),deduction=current.subtract(target);
            if(deduction.signum()<=0)continue;
            Long beneficiary=longValue(row.get("beneficiary_id"),0L);Map<String,Object> before=commissionAccount(beneficiary);
            boolean pending="待结算".equals(stringValue(row.get("status")));
            if(pending)jdbc.update("UPDATE mall_distributor SET pending_commission=pending_commission-? WHERE customer_id=?",deduction,beneficiary);
            else jdbc.update("UPDATE mall_distributor SET commission_balance=commission_balance-? WHERE customer_id=?",deduction,beneficiary);
            addCommissionLedger(longValue(row.get("id"),null),beneficiary,"售后佣金冲正",deduction.negate(),"REFUND-"+refundNo+"-"+row.get("id"),"仅撤回已全退商品固定提成，未退商品保留",before);
            jdbc.update("UPDATE mall_commission SET amount=?,status=CASE WHEN ?=0 THEN '已冲销' ELSE status END,settlement_status=CASE WHEN ?=0 THEN '已撤销' ELSE settlement_status END,reverse_reason='售后按商品冲正' WHERE id=?",target,target,target,row.get("id"));
        }
    }

    private void cancelCommissions(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,beneficiary_id,amount,status,level_no FROM mall_commission WHERE order_id=? AND status IN ('待结算','已结算') FOR UPDATE",
                orderId);
        for (Map<String, Object> row : rows)
        {
            Long beneficiary=longValue(row.get("beneficiary_id"),0L);
            Map<String,Object> before=commissionAccount(beneficiary);
            if ("待结算".equals(String.valueOf(row.get("status"))))
            {
                jdbc.update("UPDATE mall_distributor SET pending_commission=GREATEST(0,pending_commission-?) WHERE customer_id=?",
                        row.get("amount"), row.get("beneficiary_id"));
            }
            else
            {
                // 已打款佣金也必须形成负余额，避免退款后仍可重复提现。
                jdbc.update("UPDATE mall_distributor SET commission_balance=commission_balance-? WHERE customer_id=?",
                        row.get("amount"), row.get("beneficiary_id"));
            }
            addCommissionLedger(longValue(row.get("id"),null),beneficiary,"佣金冲正",decimalValue(row.get("amount")).negate(),
                    "ORDER_REVERSE-"+orderId+"-"+row.get("level_no"),"订单取消、退款或无效，保留冲正审计",before);
        }
        jdbc.update("UPDATE mall_commission SET status=CASE WHEN status='已结算' THEN '已冲销' ELSE '已取消' END,settlement_status='已撤销',reversed_time=NOW(),reverse_reason='订单取消、退款或无效' "
                + "WHERE order_id=? AND status IN ('待结算','已结算')", orderId);
    }

    private Map<String,Object> activeCommissionRule(int level,BigDecimal paidAmount)
    {
        return first("SELECT id,rate FROM mall_commission_rule WHERE level_no=? AND status='0' AND effective_from<=NOW() "
                +"AND (effective_to IS NULL OR effective_to>NOW()) AND min_order_amount<=? ORDER BY effective_from DESC,id DESC LIMIT 1",level,paidAmount);
    }

    private Map<String,Object> commissionAccount(Long customerId)
    {
        return one("SELECT commission_balance,pending_commission,frozen_commission FROM mall_distributor WHERE customer_id=? FOR UPDATE",customerId);
    }

    private void addCommissionLedger(Long commissionId,Long customerId,String type,BigDecimal amount,String businessNo,String remark,Map<String,Object> before)
    {
        Map<String,Object> after=one("SELECT commission_balance,pending_commission,frozen_commission FROM mall_distributor WHERE customer_id=?",customerId);
        jdbc.update("INSERT IGNORE INTO mall_commission_ledger(ledger_no,commission_id,customer_id,business_type,amount,available_before,available_after,pending_before,pending_after,frozen_before,frozen_after,business_no,remark) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)","CL"+UUID.randomUUID().toString().replace("-","").toUpperCase(),commissionId,customerId,type,amount,
                decimalValue(before.get("commission_balance")),decimalValue(after.get("commission_balance")),decimalValue(before.get("pending_commission")),decimalValue(after.get("pending_commission")),
                decimalValue(before.get("frozen_commission")),decimalValue(after.get("frozen_commission")),businessNo,remark);
    }

    private BigDecimal validateCoupon(Long customerId,Long couponId,BigDecimal total,List<Map<String,Object>> items)
    {
        Map<String,Object> c=first("SELECT cc.id,cc.status,t.discount_amount,t.min_order_amount,t.scope_type,t.scope_value FROM mall_customer_coupon cc "
                +"JOIN mall_coupon_template t ON t.id=cc.template_id WHERE cc.id=? AND cc.customer_id=? AND cc.status='未使用' AND t.status='0' "
                +"AND t.valid_from<=NOW() AND t.valid_to>=NOW() FOR UPDATE",couponId,customerId);
        if(c==null)throw new IllegalArgumentException("优惠券不存在、已失效或不属于当前用户");
        if(total.compareTo(decimalValue(c.get("min_order_amount")))<0)throw new IllegalArgumentException("订单金额未达到优惠券使用门槛");
        String scope=stringValue(c.get("scope_type")),value=stringValue(c.get("scope_value"));
        if("指定商品".equals(scope)){boolean ok=false;for(Map<String,Object> i:items)if((","+value+",").contains(","+i.get("id")+","))ok=true;if(!ok)throw new IllegalArgumentException("优惠券不适用于当前商品");}
        if("指定分类".equals(scope)){boolean ok=false;for(Map<String,Object> i:items)if((","+value+",").contains(","+i.get("category")+","))ok=true;if(!ok)throw new IllegalArgumentException("优惠券不适用于当前商品分类");}
        return decimalValue(c.get("discount_amount")).min(total).setScale(2,RoundingMode.HALF_UP);
    }

    private void lockCoupon(Long customerId,Long couponId,Long orderId,String orderNo,BigDecimal amount)
    {
        int n=jdbc.update("UPDATE mall_customer_coupon SET status='已锁定',locked_order_id=?,locked_time=NOW() WHERE id=? AND customer_id=? AND status='未使用'",orderId,couponId,customerId);
        if(n!=1)throw new IllegalArgumentException("优惠券状态已变化，请重新结算");
        jdbc.update("INSERT IGNORE INTO mall_coupon_log(customer_coupon_id,customer_id,action_type,order_id,business_no,amount,remark) VALUES(?,?,'锁定',?,?,?,'创建订单锁定优惠券')",couponId,customerId,orderId,orderNo,amount);
    }

    private void useCoupon(Long customerId,Long orderId,String orderNo)
    {
        Map<String,Object> c=first("SELECT id FROM mall_customer_coupon WHERE customer_id=? AND locked_order_id=? AND status='已锁定' FOR UPDATE",customerId,orderId);
        if(c==null)return;
        jdbc.update("UPDATE mall_customer_coupon SET status='已使用',used_order_id=?,used_time=NOW() WHERE id=? AND status='已锁定'",orderId,c.get("id"));
        jdbc.update("INSERT IGNORE INTO mall_coupon_log(customer_coupon_id,customer_id,action_type,order_id,business_no,amount,remark) SELECT ?,?,'核销',?,?,coupon_amount,'测试支付成功后服务端核销' FROM mall_order WHERE id=?",c.get("id"),customerId,orderId,orderNo,orderId);
    }

    private void releaseCoupon(Long orderId,String reason)
    {
        Map<String,Object> c=first("SELECT cc.id,cc.customer_id,o.order_no,o.coupon_amount FROM mall_customer_coupon cc JOIN mall_order o ON o.id=cc.locked_order_id WHERE cc.locked_order_id=? AND cc.status='已锁定' FOR UPDATE",orderId);
        if(c==null)return;
        jdbc.update("UPDATE mall_customer_coupon SET status='未使用',locked_order_id=NULL,locked_time=NULL WHERE id=? AND status='已锁定'",c.get("id"));
        jdbc.update("INSERT IGNORE INTO mall_coupon_log(customer_coupon_id,customer_id,action_type,order_id,business_no,amount,remark) VALUES(?,?,'释放',?,?,?,?)",c.get("id"),c.get("customer_id"),orderId,c.get("order_no"),c.get("coupon_amount"),reason);
    }

    private void returnUsedCoupon(Long orderId, String reason)
    {
        Map<String,Object> c=first("SELECT cc.id,cc.customer_id,o.order_no,o.coupon_amount FROM mall_customer_coupon cc "
                + "JOIN mall_order o ON o.id=cc.used_order_id WHERE cc.used_order_id=? AND cc.status='已使用' FOR UPDATE", orderId);
        if(c==null)return;
        jdbc.update("UPDATE mall_customer_coupon SET status='未使用',used_order_id=NULL,used_time=NULL,locked_order_id=NULL,locked_time=NULL WHERE id=? AND status='已使用'",c.get("id"));
        jdbc.update("INSERT IGNORE INTO mall_coupon_log(customer_coupon_id,customer_id,action_type,order_id,business_no,amount,remark) VALUES(?,?,'退款返还',?,?,?,?)",c.get("id"),c.get("customer_id"),orderId,c.get("order_no"),c.get("coupon_amount"),reason);
    }

    private int rewardPoints(BigDecimal paidAmount)
    {
        BigDecimal percent=jdbc.queryForObject("SELECT percent FROM mall_consumption_points_config WHERE id=1",BigDecimal.class);
        return paidAmount.max(BigDecimal.ZERO).multiply(percent).divide(new BigDecimal("100"),0,RoundingMode.DOWN).intValueExact();
    }

    public Map<String,Object> consumptionPointsConfig(){return one("SELECT percent,version_no AS versionNo FROM mall_consumption_points_config WHERE id=1");}
    @Transactional public void saveConsumptionPointsConfig(Map<String,Object> body){
        Map<String,Object> old=one("SELECT percent,version_no FROM mall_consumption_points_config WHERE id=1 FOR UPDATE");
        if(!String.valueOf(old.get("version_no")).equals(String.valueOf(body.get("versionNo"))))throw new IllegalArgumentException("积分比例已更新，请刷新后重试");
        BigDecimal percent=decimalValue(body.get("percent"));
        if(!body.containsKey("percent")||percent.signum()<0||percent.compareTo(new BigDecimal("10000"))>0||percent.stripTrailingZeros().scale()>2)throw new IllegalArgumentException("积分百分比需为0至10000，最多两位小数");
        jdbc.update("UPDATE mall_consumption_points_config SET percent=?,version_no=version_no+1,update_time=NOW() WHERE id=1",percent);
        jdbc.update("INSERT INTO mall_consumption_points_audit(operator_id,old_percent,new_percent) VALUES(?,?,?)",SecurityUtils.getUserId(),old.get("percent"),percent);
    }

    private void awardOrderPoints(Map<String, Object> order)
    {
        Long orderId = ((Number) order.get("id")).longValue();
        Long customerId = ((Number) order.get("customer_id")).longValue();
        String orderNo = stringValue(order.get("order_no"));
        int points = intValue(order.get("reward_points"),0); // Preserve the rate agreed at checkout.
        recordPendingOrderPoints(order);
        Map<String,Object> accrual=first("SELECT original_points,reversed_points,status FROM mall_points_accrual WHERE order_id=? FOR UPDATE",orderId);
        int effective=accrual==null?points:Math.max(0,intValue(accrual.get("original_points"),points)-intValue(accrual.get("reversed_points"),0));
        if (effective > 0 && (accrual==null || !"已生效".equals(stringValue(accrual.get("status")))))
        {
            addPointsUnique(customerId, effective, "购买商品", "订单号：" + orderNo,
                    "ORDER_REWARD", orderNo, orderId);
        }
        jdbc.update("UPDATE mall_points_accrual SET available_points=?,status='已生效',effective_time=COALESCE(effective_time,NOW()) WHERE order_id=?",effective,orderId);
    }

    private void reverseOrderReward(Map<String, Object> order, BigDecimal refundAmount, String refundNo)
    {
        Long orderId = ((Number) order.get("orderId")).longValue();
        Long customerId = ((Number) order.get("customer_id")).longValue();
        String orderNo = stringValue(order.get("order_no"));
        Map<String,Object> accrual=first("SELECT id,original_points,reversed_points,status FROM mall_points_accrual WHERE order_id=? FOR UPDATE",orderId);
        int awarded=accrual==null?0:Math.max(0,intValue(accrual.get("original_points"),0));
        Map<String, Object> award = first("SELECT amount FROM mall_points_log WHERE customer_id=? AND "
                + "((business_type='ORDER_REWARD' AND business_key=?) OR "
                + "(title='购买商品' AND description=?)) ORDER BY id LIMIT 1",
                customerId, orderNo, "订单号：" + orderNo);
        if(awarded==0&&award!=null)awarded=Math.max(0,intValue(award.get("amount"),0));
        if(awarded==0)return;
        int already=accrual==null?0:intValue(accrual.get("reversed_points"),0);
        Map<String,Object> totals=one("SELECT paid_amount,refunded_amount FROM mall_order WHERE id=?",orderId);
        BigDecimal paidTotal=decimalValue(totals.get("paid_amount"));
        int target=paidTotal.signum()<=0?awarded:new BigDecimal(awarded).multiply(decimalValue(totals.get("refunded_amount"))).divide(paidTotal,0,RoundingMode.DOWN).min(new BigDecimal(awarded)).intValue();
        int reversal = Math.max(0,target-already);
        if (reversal <= 0) return;
        String businessKey = refundNo == null || refundNo.trim().isEmpty() ? orderNo : refundNo.trim();
        if(accrual!=null&&"待生效".equals(stringValue(accrual.get("status"))))
        {
            // MySQL evaluates single-table SET assignments left-to-right: decide
            // status before incrementing, otherwise this refund is counted twice.
            jdbc.update("UPDATE mall_points_accrual SET status=CASE WHEN reversed_points+?>=original_points THEN '已撤销' ELSE status END,reversed_points=reversed_points+? WHERE id=?",reversal,reversal,accrual.get("id"));
            return;
        }
        int balance=jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=? FOR UPDATE",Integer.class,customerId);
        int debited=Math.min(balance,reversal);
        if(debited>0)addPointsUnique(customerId,-debited,"退款扣回积分","订单号："+orderNo,"ORDER_REWARD_REVERSAL",businessKey,orderId);
        int debt=reversal-debited;
        if(debt>0)jdbc.update("INSERT INTO mall_points_debt(debt_no,customer_id,order_id,business_no,points_amount,reason,status) VALUES(?,?,?,?,?,?,'待补扣') ON DUPLICATE KEY UPDATE points_amount=GREATEST(points_amount,VALUES(points_amount)),reason=VALUES(reason)",serial("PD"),customerId,orderId,businessKey,debt,"退款积分已被使用，等待后续补扣");
        if(accrual!=null)jdbc.update("UPDATE mall_points_accrual SET reversed_points=reversed_points+?,available_points=GREATEST(0,available_points-?) WHERE id=?",reversal,reversal,accrual.get("id"));
    }

    private void recordPendingOrderPoints(Map<String,Object> order)
    {
        Long orderId=longValue(order.get("id"),null); Long customerId=longValue(order.get("customer_id"),null);
        if(orderId==null||customerId==null)return;
        String orderNo=stringValue(order.get("order_no"));
        int points=intValue(order.get("reward_points"),rewardPoints(decimalValue(order.get("paid_amount"))));
        if(points<=0)return;
        jdbc.update("INSERT INTO mall_points_accrual(customer_id,order_id,order_no,original_points,status) VALUES(?,?,?,?,'待生效') ON DUPLICATE KEY UPDATE original_points=GREATEST(original_points,VALUES(original_points))",customerId,orderId,orderNo,points);
    }

    private void cancelPendingOrderPoints(Long orderId)
    {
        jdbc.update("UPDATE mall_points_accrual SET status='已撤销',reversed_points=original_points WHERE order_id=? AND status='待生效'",orderId);
    }

    private boolean addPointsUnique(Long customerId, int amount, String title, String description,
            String businessType, String businessKey, Long orderId)
    {
        if (amount == 0) throw new IllegalArgumentException("积分变更数量不能为0");
        if (businessKey == null || businessKey.trim().isEmpty())
            throw new IllegalArgumentException("积分流水业务标识不能为空");
        Map<String, Object> customer = one("SELECT points FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        int exists = jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_log WHERE customer_id=? "
                + "AND business_type=? AND business_key=?", Integer.class, customerId, businessType, businessKey);
        if (exists > 0) return false;
        int balanceBefore = intValue(customer.get("points"), 0);
        int balanceAfter = balanceBefore + amount;
        if (balanceAfter < 0) throw new IllegalArgumentException("积分不足");
        jdbc.update("UPDATE mall_customer SET points=? WHERE id=?", balanceAfter, customerId);
        jdbc.update("INSERT INTO mall_points_log(customer_id,title,description,remark,business_type,business_key,"
                        + "related_business_no,order_id,amount,balance_before,balance_after,balance) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", customerId, title, description, description, businessType,
                businessKey, businessKey, orderId, amount, balanceBefore, balanceAfter, balanceAfter);
        return true;
    }

    private Map<String, Object> taskQualification(Long customerId, String businessType)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> source = null;
        if ("CHECKIN".equals(businessType))
        {
            String day = LocalDate.now(SHANGHAI_ZONE).toString();
            source = first("SELECT id,? AS businessKey FROM mall_points_checkin WHERE customer_id=? AND checkin_date=?", day, customerId, day);
        }
        else if ("ORDER".equals(businessType))
        {
            source = first("SELECT id,order_no AS businessKey FROM mall_order WHERE customer_id=? AND status='已完成' "
                    + "AND payment_status='已支付' ORDER BY paid_time,id LIMIT 1", customerId);
        }
        else if ("REVIEW".equals(businessType))
        {
            source = first("SELECT id,CAST(id AS CHAR) AS businessKey FROM mall_review WHERE customer_id=? AND status='0' "
                    + "ORDER BY create_time,id LIMIT 1", customerId);
        }
        else if ("INVITE".equals(businessType))
        {
            source = first("SELECT id,CAST(id AS CHAR) AS businessKey FROM mall_invite_record "
                    + "WHERE inviter_customer_id=? AND completed_time IS NOT NULL ORDER BY completed_time,id LIMIT 1", customerId);
        }
        result.put("completed", source != null);
        result.put("businessKey", source == null ? "" : stringValue(source.get("businessKey")));
        return result;
    }

    private int tierProgress(Long customerId, String metricType)
    {
        if ("COMPLETED_INVITES".equals(metricType))
            return jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=? "
                            + "AND completed_time IS NOT NULL",
                    Integer.class, customerId);
        if ("COMPLETED_ORDERS".equals(metricType))
            return jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE customer_id=? AND status='已完成' AND payment_status='已支付'",
                    Integer.class, customerId);
        if ("ORDER_AMOUNT".equals(metricType))
            return jdbc.queryForObject("SELECT COALESCE(FLOOR(SUM(paid_amount)),0) FROM mall_order WHERE customer_id=? "
                    + "AND payment_status='已支付' AND status NOT IN ('已取消','已退款')", Integer.class, customerId);
        throw new IllegalArgumentException("不支持的阶梯进度类型");
    }

    private Map<String, Object> ledgerSummary(Long customerId)
    {
        int balance = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId);
        Map<String, Object> aggregate = one("SELECT COUNT(*) AS entryCount,COALESCE(SUM(amount),0) AS delta," 
                + "COALESCE(SUM(CASE WHEN balance_after-balance_before<>amount THEN 1 ELSE 0 END),0) AS brokenEntries "
                + "FROM mall_points_log WHERE customer_id=?", customerId);
        Map<String, Object> firstLog = first("SELECT balance_before AS openingBalance FROM mall_points_log WHERE customer_id=? ORDER BY id LIMIT 1", customerId);
        Map<String, Object> lastLog = first("SELECT balance_after AS lastBalance FROM mall_points_log WHERE customer_id=? ORDER BY id DESC LIMIT 1", customerId);
        int opening = firstLog == null ? balance : intValue(firstLog.get("openingBalance"), 0);
        int delta = intValue(aggregate.get("delta"), 0);
        int expected = opening + delta;
        int broken = intValue(aggregate.get("brokenEntries"), 0);
        boolean consistent = expected == balance && (lastLog == null || intValue(lastLog.get("lastBalance"), balance) == balance) && broken == 0;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("openingBalance", opening);
        result.put("delta", delta);
        result.put("expectedBalance", expected);
        result.put("currentBalance", balance);
        result.put("entryCount", aggregate.get("entryCount"));
        result.put("brokenEntries", broken);
        result.put("consistent", consistent);
        return result;
    }

    private void attachProductCatalog(List<Map<String, Object>> products)
    {
        attachProductMedia(products);
        for (Map<String, Object> product : products)
        {
            Long productId = longValue(product.get("id"), null);
            if (productId == null) continue;
            List<Map<String, Object>> skus = jdbc.queryForList(
                    "SELECT id AS skuId,sku_code AS skuCode,spec_name AS spec,price,reward_points AS reward,"
                  + "stock,sales,is_default AS isDefault,status FROM mall_product_sku "
                  + "WHERE product_id=? ORDER BY is_default DESC,id", productId);
            product.put("skus", skus);
            for (Map<String, Object> sku : skus)
            {
                if ("0".equals(stringValue(sku.get("status"))) && intValue(sku.get("isDefault"), 0) == 1)
                {
                    product.put("defaultSkuId", sku.get("skuId"));
                    break;
                }
            }
        }
    }

    private void attachProductMedia(List<Map<String, Object>> products)
    {
        for (Map<String, Object> product : products)
        {
            Long productId = ((Number) product.get("id")).longValue();
            List<Map<String, Object>> media = jdbc.queryForList(
                    "SELECT id,media_type AS type,media_url AS url,sort_no AS sortNo "
                  + "FROM mall_product_media WHERE product_id=? ORDER BY sort_no,id", productId);
            List<String> gallery = new ArrayList<String>();
            List<String> details = new ArrayList<String>();
            for (Map<String, Object> item : media)
            {
                String type = stringValue(item.get("type"));
                String url = stringValue(item.get("url"));
                if ("detail".equals(type)) details.add(url);
                else if ("gallery".equals(type)) gallery.add(url);
            }
            product.put("media", media);
            product.put("galleryImages", gallery);
            product.put("detailImages", details);
        }
    }

    private void syncProductMedia(Long productId, Map<String, Object> body)
    {
        jdbc.update("DELETE FROM mall_product_media WHERE product_id=?", productId);
        String mainImage = stringValue(body.get("imageKey")).trim();
        if (!mainImage.isEmpty())
        {
            jdbc.update("INSERT INTO mall_product_media(product_id,media_type,media_url,sort_no) VALUES(?,'main',?,0)",
                    productId, mainImage);
        }
        insertProductMediaList(productId, "gallery", body.get("galleryImages"), 10);
        insertProductMediaList(productId, "detail", body.get("detailImages"), 1000);
    }

    private void insertProductMediaList(Long productId, String type, Object value, int startSort)
    {
        List<String> values = new ArrayList<String>();
        if (value instanceof Iterable)
        {
            for (Object item : (Iterable<?>) value)
            {
                String url = stringValue(item).trim();
                if (!url.isEmpty() && !values.contains(url)) values.add(url);
            }
        }
        else
        {
            for (String item : stringValue(value).split("[\\n,]"))
            {
                String url = item.trim();
                if (!url.isEmpty() && !values.contains(url)) values.add(url);
            }
        }
        for (int index = 0; index < values.size(); index++)
        {
            jdbc.update("INSERT INTO mall_product_media(product_id,media_type,media_url,sort_no) VALUES(?,?,?,?)",
                    productId, type, values.get(index), startSort + index);
        }
    }

    private void validateSlug(String slug)
    {
        if (!slug.matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$"))
            throw new IllegalArgumentException("专题标识需为3-64位小写字母、数字或连字符");
    }

    private Object emptyToNull(Object value)
    {
        String text = stringValue(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void syncTopicProducts(Long topicId, Object value)
    {
        List<Long> productIds = new ArrayList<Long>();
        if (value instanceof Iterable)
        {
            for (Object item : (Iterable<?>) value)
            {
                Long productId = longValue(item, null);
                if (productId != null && !productIds.contains(productId)) productIds.add(productId);
            }
        }
        else
        {
            for (String item : stringValue(value).split("[,，\\s]+"))
            {
                Long productId = longValue(item, null);
                if (productId != null && !productIds.contains(productId)) productIds.add(productId);
            }
        }
        for (Long productId : productIds) requireProductAnyStatus(productId);
        jdbc.update("DELETE FROM mall_topic_product WHERE topic_id=?", topicId);
        for (int index=0; index<productIds.size(); index++)
            jdbc.update("INSERT INTO mall_topic_product(topic_id,product_id,sort_no) VALUES(?,?,?)",
                    topicId, productIds.get(index), (index + 1) * 10);
    }

    private Map<String, Object> requireProductAnyStatus(Long productId)
    {
        if (productId == null) throw new IllegalArgumentException("请选择商品");
        Map<String, Object> product = first("SELECT id,name,status FROM mall_product WHERE id=?", productId);
        if (product == null) throw new IllegalArgumentException("商品不存在");
        return product;
    }

    private void requireUniqueSku(Long skuId, Long productId, String skuCode, String spec)
    {
        if (skuCode.length() > 64 || !skuCode.matches("^[A-Za-z0-9_-]{3,64}$"))
            throw new IllegalArgumentException("SKU编码需为3-64位字母、数字、横线或下划线");
        if (spec.isEmpty() || spec.length() > 128) throw new IllegalArgumentException("规格名称不能为空且不能超过128个字");
        int codeCount = skuId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE sku_code=?", Integer.class, skuCode)
                : jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE sku_code=? AND id<>?", Integer.class, skuCode, skuId);
        if (codeCount > 0) throw new IllegalArgumentException("SKU编码已存在");
        int specCount = skuId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE product_id=? AND spec_name=?", Integer.class, productId, spec)
                : jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE product_id=? AND spec_name=? AND id<>?", Integer.class, productId, spec, skuId);
        if (specCount > 0) throw new IllegalArgumentException("该商品已存在同名规格");
    }

    private void ensureDefaultSku(Long productId)
    {
        int defaults = jdbc.queryForObject("SELECT COUNT(*) FROM mall_product_sku WHERE product_id=? AND is_default=1 AND status='0'",
                Integer.class, productId);
        if (defaults == 0)
            jdbc.update("UPDATE mall_product_sku SET is_default=1 WHERE product_id=? AND status='0' ORDER BY id LIMIT 1", productId);
    }

    private void syncProductFromSkus(Long productId)
    {
        Map<String, Object> sku = first("SELECT spec_name,price,reward_points FROM mall_product_sku "
                + "WHERE product_id=? AND status='0' ORDER BY is_default DESC,id LIMIT 1", productId);
        if (sku == null) throw new IllegalArgumentException("商品必须至少保留一个在售规格");
        jdbc.update("UPDATE mall_product SET spec=?,price=?,reward_points=?,"
                + "stock=(SELECT COALESCE(SUM(stock),0) FROM mall_product_sku WHERE product_id=? AND status='0'),"
                + "sales=(SELECT COALESCE(SUM(sales),0) FROM mall_product_sku WHERE product_id=?) WHERE id=?",
                sku.get("spec_name"),sku.get("price"),sku.get("reward_points"),productId,productId,productId);
    }

    private void syncDefaultSku(Long productId, String spec, BigDecimal price, int stock, String status)
    {
        Map<String, Object> sku = first("SELECT id FROM mall_product_sku WHERE product_id=? ORDER BY is_default DESC,id LIMIT 1", productId);
        String normalizedSpec = spec == null || spec.trim().isEmpty() ? "默认规格" : spec.trim();
        if (sku == null)
        {
            String code = "TEA-" + String.format("%06d", productId) + "-DEFAULT";
            jdbc.update("INSERT INTO mall_product_sku(product_id,sku_code,spec_name,price,reward_points,stock,sales,is_default,status) "
                    + "VALUES(?,?,?,?,?,?,0,1,?)",productId,code,normalizedSpec,price,rewardPoints(price),Math.max(0,stock),status);
        }
        else
        {
            jdbc.update("UPDATE mall_product_sku SET spec_name=?,price=?,reward_points=?,stock=?,status=?,is_default=1 WHERE id=?",
                    normalizedSpec,price,rewardPoints(price),Math.max(0,stock),status,sku.get("id"));
        }
    }

    private void ensureCustomer(Long customerId)
    {
        Map<String, Object> customer = first("SELECT id,status FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        String status = customer == null ? "" : stringValue(customer.get("status"));
        if (customer == null || "1".equals(status) || "2".equals(status) || "3".equals(status))
            throw new MallAuthorizationException("用户不存在或已被禁用");
    }

    private void requireCompleteAddress(Map<String, Object> address)
    {
        if (address == null || stringValue(address.get("receiver_name")).trim().isEmpty()
                || !stringValue(address.get("receiver_phone")).trim().matches("^1\\d{10}$")
                || stringValue(address.get("region")).trim().isEmpty()
                || stringValue(address.get("detail_address")).trim().isEmpty())
        {
            throw new IllegalArgumentException("请填写完整的收货人、手机号和收货地址");
        }
    }

    private Map<String, Object> requireProduct(Long productId)
    {
        Map<String, Object> product = first("SELECT " + PRODUCT_COLUMNS + " FROM mall_product p WHERE p.id=? AND p.status='0'", productId);
        if (product == null)
        {
            throw new IllegalArgumentException("商品不存在或已下架");
        }
        return product;
    }

    private Map<String, Object> requireSku(Long productId, Long skuId)
    {
        if (productId == null) throw new IllegalArgumentException("请选择商品");
        requireProduct(productId);
        Map<String, Object> sku = skuId == null
                ? first("SELECT id AS skuId,sku_code AS skuCode,spec_name AS spec,price,reward_points AS reward,stock,sales "
                      + "FROM mall_product_sku WHERE product_id=? AND status='0' ORDER BY is_default DESC,id LIMIT 1", productId)
                : first("SELECT id AS skuId,sku_code AS skuCode,spec_name AS spec,price,reward_points AS reward,stock,sales "
                      + "FROM mall_product_sku WHERE id=? AND product_id=? AND status='0'", skuId, productId);
        if (sku == null) throw new IllegalArgumentException("所选商品规格不存在或已停用");
        return sku;
    }

    private Map<String, Object> publicStoreSummary(Long storeId, Long customerId)
    {
        Long normalizedStoreId = storeId == null ? 1L : storeId;
        Map<String, Object> store = first("SELECT s.id,s.name,s.logo_url AS logoUrl,s.hero_image_url AS heroImageUrl,"
                + "s.rating,s.follower_count AS followerCount,s.story,s.shipping_promise AS shippingPromise,"
                + "s.service_promise AS servicePromise,s.status,"
                + "(SELECT COUNT(*) FROM mall_product p WHERE p.store_id=s.id AND p.status='0') AS productCount "
                + "FROM mall_store s WHERE s.id=? AND s.status='0'", normalizedStoreId);
        if (store == null) throw new IllegalArgumentException("店铺不存在或已停业");
        boolean followed = customerId != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_store_favorite WHERE store_id=? AND customer_id=?",
                Integer.class, normalizedStoreId, customerId) > 0;
        store.put("followed", followed);
        store.put("actualFollowerCount", intValue(store.get("followerCount"), 0) + jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_store_favorite WHERE store_id=?", Integer.class, normalizedStoreId));
        return store;
    }

    private void notifyCustomer(Long customerId, String category, String title, String content,
            String targetRoute, String targetQuery, String sourceType, String sourceKey, String eventCode)
    {
        if (customerId == null || customerId <= 0) return;
        jdbc.update("INSERT IGNORE INTO mall_notification(customer_id,category,title,content,target_route,target_query,"
                + "source_type,source_key,event_code) VALUES(?,?,?,?,?,?,?,?,?)", customerId, category, title,
                content, targetRoute, targetQuery, sourceType, sourceKey, eventCode);
    }

    private void addPoints(Long customerId, int amount, String title, String description)
    {
        jdbc.update("UPDATE mall_customer SET points=points+? WHERE id=?", amount, customerId);
        int balance = jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?", Integer.class, customerId);
        jdbc.update("INSERT INTO mall_points_log(customer_id,title,description,amount,balance) VALUES(?,?,?,?,?)",
                customerId, title, description, amount, balance);
    }

    private long count(String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Map<String, Object> one(String sql, Object... args)
    {
        Map<String, Object> value = first(sql, args);
        if (value == null)
        {
            throw new IllegalArgumentException("数据不存在");
        }
        return value;
    }

    private Map<String, Object> publicOrderResult(Long orderId, boolean idempotent)
    {
        Map<String, Object> result = one("SELECT id,order_no AS orderNo,order_no AS no,status,total_amount AS totalAmount,"
                + "shipping_fee AS shippingFee,discount_amount AS discountAmount,coupon_amount AS couponAmount,"
                + "points_discount AS pointsDiscount,points_used AS pointsUsed,paid_amount AS paidAmount,reward_points AS rewardPoints,"
                + "payment_method AS paymentMethod,payment_status AS paymentStatus,paid_time AS paidTime,is_test_order AS isTestOrder,"
                + "receiver_name AS receiverName,receiver_phone AS receiverPhone,receiver_address AS receiverAddress,"
                + "cancel_reason AS cancelReason,cancel_note AS cancelNote,cancel_time AS cancelTime,"
                + "aftersale_status AS aftersaleStatus,refunded_amount AS refundedAmount,"
                + "carrier,tracking_no AS trackingNo,create_time AS createTime "
                + "FROM mall_order WHERE id=?", orderId);
        result.put("items", jdbc.queryForList(
                "SELECT product_id AS productId,sku_id AS skuId,sku_code AS skuCode,product_name AS name,spec,price,qty,"
              + "id AS orderItemId,refunded_qty AS refundedQty,aftersale_locked_qty AS aftersaleLockedQty,"
              + "image_key AS imageKey,price*qty AS subtotal FROM mall_order_item WHERE order_id=? ORDER BY id", orderId));
        result.put("aftersales", jdbc.queryForList("SELECT aftersale_no AS aftersaleNo,type_name AS typeName,status,order_item_id AS orderItemId,"
                + "apply_qty AS qty,requested_amount AS requestedAmount,refund_amount AS refundAmount,create_time AS createTime "
                + "FROM mall_aftersale WHERE order_id=? ORDER BY id DESC", orderId));
        result.put("operations", jdbc.queryForList("SELECT old_status AS oldStatus,new_status AS newStatus,reason,source_name AS sourceName,create_time AS createTime "
                + "FROM mall_order_operation_log WHERE order_id=? ORDER BY id", orderId));
        result.put("idempotent", idempotent);
        result.put("testPayment", "测试支付".equals(stringValue(result.get("paymentMethod"))));
        return result;
    }

    private String textListValue(Object value)
    {
        if (!(value instanceof Iterable)) return stringValue(value).trim();
        StringBuilder text = new StringBuilder();
        for (Object item : (Iterable<?>) value)
        {
            String row = stringValue(item).trim();
            if (row.isEmpty()) continue;
            if (text.length() > 0) text.append("\n");
            text.append(row);
        }
        return text.toString();
    }

    private void logOrderTransition(Map<String, Object> order, String operatorType, Long operatorId,
            String sourceName, String oldStatus, String newStatus, String reason, String requestNo)
    {
        Long orderId = longValue(order.containsKey("orderId") ? order.get("orderId") : order.get("id"), null);
        String orderNo = stringValue(order.containsKey("orderNo") ? order.get("orderNo") : order.get("order_no"));
        jdbc.update("INSERT IGNORE INTO mall_order_operation_log(order_id,order_no,operator_type,operator_id,source_name,old_status,new_status,reason,request_no) "
                + "VALUES(?,?,?,?,?,?,?,?,?)", orderId, orderNo, operatorType, operatorId, sourceName,
                oldStatus, newStatus, stringValue(reason), requestNo == null || requestNo.trim().isEmpty() ? null : requestNo);
    }

    private void logAftersaleTransition(Long aftersaleId, String aftersaleNo, String operatorType, Long operatorId,
            String sourceName, String oldStatus, String newStatus, String remark, String requestNo)
    {
        jdbc.update("INSERT IGNORE INTO mall_aftersale_operation_log(aftersale_id,aftersale_no,operator_type,operator_id,source_name,old_status,new_status,remark,request_no) "
                + "VALUES(?,?,?,?,?,?,?,?,?)", aftersaleId, aftersaleNo, operatorType, operatorId, sourceName,
                oldStatus, newStatus, stringValue(remark), requestNo == null || requestNo.trim().isEmpty() ? null : requestNo);
    }

    private void releaseAftersaleQuantity(Map<String, Object> sale, boolean refunded)
    {
        Long itemId = longValue(sale.get("order_item_id"), null);
        int qty = intValue(sale.get("apply_qty"), 0);
        if (itemId == null || qty <= 0) return;
        jdbc.update("UPDATE mall_order_item SET aftersale_locked_qty=GREATEST(0,aftersale_locked_qty-?),"
                + (refunded ? "refunded_qty=LEAST(qty,refunded_qty+?) " : "refunded_qty=refunded_qty ")
                + "WHERE id=?", refunded ? new Object[]{qty, qty, itemId} : new Object[]{qty, itemId});
    }

    private void restoreAftersaleInventory(Map<String, Object> sale)
    {
        if (boolValue(sale.get("inventory_restored"), false)) return;
        int qty = intValue(sale.get("apply_qty"), 0);
        if (qty <= 0) return;
        jdbc.update("UPDATE mall_product SET stock=stock+?,sales=GREATEST(0,sales-?) WHERE id=?", qty, qty, sale.get("product_id"));
        Long skuId = longValue(sale.get("sku_id"), null);
        if (skuId != null) jdbc.update("UPDATE mall_product_sku SET stock=stock+?,sales=GREATEST(0,sales-?) WHERE id=?", qty, qty, skuId);
        jdbc.update("UPDATE mall_aftersale SET inventory_restored=1 WHERE id=? AND inventory_restored=0", sale.get("id"));
    }

    private void restoreOrderAfterAftersaleClosed(Long orderId)
    {
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM mall_aftersale WHERE order_id=? AND status NOT IN ('审核拒绝','用户已撤销','已关闭','售后完成')", Integer.class, orderId);
        if (active > 0) return;
        Map<String, Object> order = one("SELECT id,status,paid_amount,refunded_amount FROM mall_order WHERE id=? FOR UPDATE", orderId);
        BigDecimal paid = decimalValue(order.get("paid_amount"));
        BigDecimal refunded = decimalValue(order.get("refunded_amount"));
        if (refunded.compareTo(paid) >= 0 && paid.compareTo(BigDecimal.ZERO) > 0)
            jdbc.update("UPDATE mall_order SET status='已退款',payment_status='已退款',aftersale_status='全额退款完成' WHERE id=?", orderId);
        else if (refunded.compareTo(BigDecimal.ZERO) > 0)
        {
            String fulfillmentStatus = effectiveFulfillmentStatus(order);
            jdbc.update("UPDATE mall_order SET status=?,aftersale_status='部分售后完成' WHERE id=?", fulfillmentStatus, orderId);
        }
        else
            jdbc.update("UPDATE mall_order SET status=?,aftersale_status='' WHERE id=?", effectiveFulfillmentStatus(order), orderId);
    }

    private String effectiveFulfillmentStatus(Map<String, Object> order)
    {
        String status = stringValue(order.get("status"));
        if (!("售后中".equals(status) || "部分售后完成".equals(status))) return status;
        Long orderId = longValue(order.get("id"), null);
        if (orderId == null) return "已完成";
        Map<String, Object> source = first("SELECT source_order_status FROM mall_aftersale WHERE order_id=? "
                + "AND source_order_status IN ('待发货','待收货','已完成') ORDER BY id DESC LIMIT 1", orderId);
        return source == null ? "已完成" : stringValue(source.get("source_order_status"));
    }

    private void allocateOrderItemAmounts(List<Map<String, Object>> items, BigDecimal total,
            BigDecimal couponAmount, BigDecimal pointsDiscount, BigDecimal shippingFee)
    {
        BigDecimal couponRemaining = couponAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal pointsRemaining = pointsDiscount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal shippingRemaining = shippingFee.setScale(2, RoundingMode.HALF_UP);
        for (int index = 0; index < items.size(); index++)
        {
            Map<String, Object> item = items.get(index);
            BigDecimal gross = decimalValue(item.get("price"))
                    .multiply(BigDecimal.valueOf(intValue(item.get("qty"), 1))).setScale(2, RoundingMode.HALF_UP);
            boolean last = index == items.size() - 1;
            BigDecimal coupon = last ? couponRemaining : proportionalAllocation(couponAmount, gross, total).min(couponRemaining);
            BigDecimal points = last ? pointsRemaining : proportionalAllocation(pointsDiscount, gross, total).min(pointsRemaining);
            BigDecimal shipping = last ? shippingRemaining : proportionalAllocation(shippingFee, gross, total).min(shippingRemaining);
            couponRemaining = couponRemaining.subtract(coupon).max(BigDecimal.ZERO);
            pointsRemaining = pointsRemaining.subtract(points).max(BigDecimal.ZERO);
            shippingRemaining = shippingRemaining.subtract(shipping).max(BigDecimal.ZERO);
            item.put("grossAmount", gross);
            item.put("couponAllocated", coupon);
            item.put("pointsAllocated", points);
            item.put("shippingAllocated", shipping);
            item.put("refundableAmount", gross.subtract(coupon).subtract(points).add(shipping).max(BigDecimal.ZERO));
        }
    }

    private BigDecimal proportionalAllocation(BigDecimal amount, BigDecimal lineGross, BigDecimal totalGross)
    {
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || totalGross.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO.setScale(2);
        return amount.multiply(lineGross).divide(totalGross, 2, RoundingMode.DOWN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> validateInvoice(Object invoiceValue)
    {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("type", "");
        result.put("title", "");
        result.put("taxNo", "");
        result.put("email", "");
        if (!(invoiceValue instanceof Map)) return result;
        Map<String, Object> invoice = (Map<String, Object>) invoiceValue;
        String type = stringValue(invoice.get("type")).trim();
        if (type.isEmpty() || "不开发票".equals(type)) return result;
        if (!("个人".equals(type) || "企业".equals(type) || "电子普通发票".equals(type)))
            throw new IllegalArgumentException("发票类型无效");
        String title = stringValue(invoice.get("title")).trim();
        String taxNo = stringValue(invoice.get("taxNo")).trim();
        String email = stringValue(invoice.get("email")).trim();
        if (title.isEmpty() || title.length() > 128) throw new IllegalArgumentException("请填写正确的发票抬头");
        if ("企业".equals(type) && !taxNo.matches("^[A-Za-z0-9]{15,20}$"))
            throw new IllegalArgumentException("请填写正确的纳税人识别号");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 128)
            throw new IllegalArgumentException("请填写正确的发票接收邮箱");
        result.put("type", type);
        result.put("title", title);
        result.put("taxNo", taxNo);
        result.put("email", email);
        return result;
    }

    private Map<String, Object> first(String sql, Object... args)
    {
        List<Map<String, Object>> list = jdbc.queryForList(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> resolveProductCategory(Map<String, Object> body)
    {
        Long categoryId = longValue(body.get("categoryId"), null);
        if (categoryId == null) throw new IllegalArgumentException("请选择商品分类");
        Map<String, Object> category = first("SELECT c.id,c.name FROM mall_category c "
                + "WHERE c.id=? AND c.status='0' AND c.parent_id IS NOT NULL", categoryId);
        if (category == null) throw new IllegalArgumentException("商品分类不存在、已停用或不是末级分类");
        return category;
    }

    private void validateProductCategoryParent(Long categoryId, Long parentId, String group)
    {
        if (!"TEA".equals(group) && !"WARE".equals(group))
            throw new IllegalArgumentException("分类分组只能为茶叶或茶具");
        if (parentId == null) return;
        if (categoryId != null && categoryId.equals(parentId))
            throw new IllegalArgumentException("分类不能把自己设为父级");
        Map<String,Object> parent = first("SELECT id,parent_id AS parentId,category_group AS categoryGroup FROM mall_category WHERE id=?",parentId);
        if (parent == null) throw new IllegalArgumentException("父分类不存在");
        if (parent.get("parentId") != null) throw new IllegalArgumentException("商城分类仅允许一级和二级结构");
        if (!group.equals(stringValue(parent.get("categoryGroup"))))
            throw new IllegalArgumentException("子分类必须与父分类属于同一分类分组");
        if (categoryId != null && jdbc.queryForObject("SELECT COUNT(*) FROM mall_category WHERE parent_id=?",Integer.class,categoryId)>0)
            throw new IllegalArgumentException("已有子分类的一级分类不能再挂到其他分类下");
    }

    private String normalizedRequestNo(Object value, String prefix)
    {
        String requestNo = stringValue(value).trim();
        if (!PAYMENT_REQUEST_PATTERN.matcher(requestNo).matches())
            throw new IllegalArgumentException(prefix + "请求号格式无效，请刷新后重试");
        return requestNo;
    }

    private String safeUploadUrl(Object value)
    {
        String url = stringValue(value).trim();
        if (url.isEmpty()) return "";
        if (!url.matches("^/profile/upload/[A-Za-z0-9_./-]{1,480}$") || url.contains(".."))
            throw new IllegalArgumentException("附件必须来自本项目安全上传目录");
        return url;
    }

    private boolean validTicketTransition(String from, String to)
    {
        if (from.equals(to)) return true;
        if ("待处理".equals(from)) return "处理中".equals(to) || "已回复".equals(to) || "已完成".equals(to);
        if ("处理中".equals(from)) return "已回复".equals(to) || "已完成".equals(to);
        if ("已回复".equals(from)) return "处理中".equals(to) || "已完成".equals(to);
        return "已完成".equals(from) && "处理中".equals(to);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value)
    {
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<Map<String, Object>>();
    }

    private String serial(String prefix)
    {
        return prefix + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String required(Map<String, Object> body, String key)
    {
        String value = stringValue(body.get(key)).trim();
        if (value.length() == 0)
        {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value;
    }

    private String stringValue(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private String galleryValue(Object value)
    {
        if (value instanceof Iterable)
        {
            StringBuilder images = new StringBuilder();
            for (Object item : (Iterable<?>) value)
            {
                String image = stringValue(item).trim();
                if (image.isEmpty()) continue;
                if (images.length() > 0) images.append("\n");
                images.append(image);
            }
            return images.toString();
        }
        return stringValue(value).replace(",", "\n").trim();
    }

    private Long longValue(Object value, Long fallback)
    {
        if (value == null || String.valueOf(value).length() == 0)
        {
            return fallback;
        }
        return Long.valueOf(String.valueOf(value));
    }

    private int intValue(Object value, int fallback)
    {
        if (value == null || String.valueOf(value).length() == 0)
        {
            return fallback;
        }
        if (value instanceof Boolean)
        {
            return Boolean.TRUE.equals(value) ? 1 : 0;
        }
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    private BigDecimal decimalValue(Object value)
    {
        return value == null || String.valueOf(value).length() == 0 ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private boolean boolValue(Object value, boolean fallback)
    {
        return value == null ? fallback : (Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)));
    }
}
