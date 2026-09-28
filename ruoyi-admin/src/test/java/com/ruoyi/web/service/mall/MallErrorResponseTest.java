package com.ruoyi.web.service.mall;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import static org.junit.jupiter.api.Assertions.*;
class MallErrorResponseTest {
 @Test void unsupportedMethodHasCorrectBusinessCode() {
  assertEquals(405,new GlobalExceptionHandler().handleHttpRequestMethodNotSupported(new org.springframework.web.HttpRequestMethodNotSupportedException("GET"),new MockHttpServletRequest()).get("code"));
 }
 @Test void databaseInternalsAreNotReturnedToClients() {
  GlobalExceptionHandler handler=new GlobalExceptionHandler();
  String result=handler.handleRuntimeException(new RuntimeException("INSERT INTO secret_table VALUES(secret)"),new MockHttpServletRequest()).toString();
  assertFalse(result.contains("secret"));assertTrue(result.contains("服务暂时不可用"));
 }
}
