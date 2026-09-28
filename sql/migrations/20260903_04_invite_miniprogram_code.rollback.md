# 20260903_04 回滚说明

应用回滚时可保留三个缓存字段。必须删除时先备份数据库，然后删除 `mall_invite_scene.mini_code_content`、`mini_code_generated_at`、`mini_code_generation_count`，并移除迁移记录。删除只会使二维码需要重新生成，不会删除邀请关系。
