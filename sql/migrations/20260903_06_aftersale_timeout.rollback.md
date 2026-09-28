# 20260903_06 回滚说明

应用回滚到不读取 `action_deadline` 的版本后，可先删除 `idx_aftersale_deadline`，再删除 `mall_aftersale.action_deadline`。超时产生的“已关闭”状态和操作日志属于真实审计记录，默认不得反向篡改；如业务要求恢复，须逐单人工审核后走新申请流程。
