package com.ruoyi.web.service.mall;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MallProductValidationTest {
 @Test void exchangeCostCannotOverflowOrBecomeNegative() {
  assertEquals(200, MallService.checkedExchangeCost(100,2));
  assertThrows(IllegalArgumentException.class,()->MallService.checkedExchangeCost(Integer.MAX_VALUE,2));
  assertThrows(IllegalArgumentException.class,()->MallService.checkedExchangeCost(0,1));
  assertThrows(IllegalArgumentException.class,()->MallService.checkedExchangeCost(1,-1));
 }
 @Test void invalidNumbersAndStatesAreRejectedBeforeAnyDatabaseWrite() {
  MallService service=new MallService();
  Object[][] invalid={{-1,1,"0"},{1,-1,"0"},{"0.001",1,"0"},{1,"1.5","0"},{"NaN",1,"0"},{1,"2147483648","0"},{null,1,"0"},{1,null,"0"},{1,1,"2"},{"1000000",1,"0"}};
  for(Object[] values:invalid) {
   Map<String,Object> body=new HashMap<>();body.put("price",values[0]);body.put("stock",values[1]);body.put("status",values[2]);
   assertThrows(IllegalArgumentException.class,()->service.adminCreateProduct(body),Arrays.toString(values));
   assertThrows(IllegalArgumentException.class,()->service.adminUpdateProduct(1L,body),Arrays.toString(values));
   assertThrows(IllegalArgumentException.class,()->service.adminCreateSku(body),Arrays.toString(values));
   assertThrows(IllegalArgumentException.class,()->service.adminUpdateSku(1L,body),Arrays.toString(values));
  }
 }
}
