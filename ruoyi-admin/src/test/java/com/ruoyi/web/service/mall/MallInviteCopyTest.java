package com.ruoyi.web.service.mall;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MallInviteCopyTest {
 @Test void acceptsMerchantPlainText(){assertEquals("立即邀请",MallTeaFriendService.displayText(" 立即邀请 ","按钮",12));}
 @Test void rejectsBlankHtmlControlsAndTooLong(){for(String s:new String[]{"","  ","<img>","邀请\n好友","1234567890123"})assertThrows(IllegalArgumentException.class,()->MallTeaFriendService.displayText(s,"按钮",12));}
 @Test void countsUnicodeCharacters(){assertEquals("🍵邀请",MallTeaFriendService.displayText("🍵邀请","标题",3));}
}
