alter table subscriptions
    drop index ix_subscriptions_keyword_id_enabled,
    add index ix_subscriptions_keyword_id_enabled_user_id (keyword_id, enabled, user_id);
