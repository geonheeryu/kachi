package me.rgunny.kachi.user.adapter.inbound.web.fake

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration(proxyBeanMethods = false)
class WebMvcFakeUseCaseConfig {

    @Bean
    fun registerUserUseCase(): FakeRegisterUserUseCase = FakeRegisterUserUseCase()

    @Bean
    fun deactivateUserUseCase(): FakeDeactivateUserUseCase = FakeDeactivateUserUseCase()

    @Bean
    fun getUserUseCase(): FakeGetUserUseCase = FakeGetUserUseCase()

    @Bean
    fun refreshTokenUseCase(): FakeRefreshTokenUseCase = FakeRefreshTokenUseCase()

    @Bean
    fun logoutUseCase(): FakeLogoutUseCase = FakeLogoutUseCase()

    @Bean
    fun registerSubscriptionUseCase(): FakeRegisterSubscriptionUseCase = FakeRegisterSubscriptionUseCase()

    @Bean
    fun updateSubscriptionUseCase(): FakeUpdateSubscriptionUseCase = FakeUpdateSubscriptionUseCase()

    @Bean
    fun listSubscriptionsUseCase(): FakeListSubscriptionsUseCase = FakeListSubscriptionsUseCase()

    @Bean
    fun registerWebhookBindingUseCase(): FakeRegisterWebhookBindingUseCase = FakeRegisterWebhookBindingUseCase()

    @Bean
    fun issueTelegramLinkUseCase(): FakeIssueTelegramLinkUseCase = FakeIssueTelegramLinkUseCase()

    @Bean
    fun completeTelegramLinkUseCase(): FakeCompleteTelegramLinkUseCase = FakeCompleteTelegramLinkUseCase()

    @Bean
    fun revokeChannelBindingUseCase(): FakeRevokeChannelBindingUseCase = FakeRevokeChannelBindingUseCase()

    @Bean
    fun listChannelBindingsUseCase(): FakeListChannelBindingsUseCase = FakeListChannelBindingsUseCase()

    @Bean
    fun findSubscribersUseCase(): FakeFindSubscribersUseCase = FakeFindSubscribersUseCase()

    @Bean
    fun findSubscriptionRecipientsUseCase(): FakeFindSubscriptionRecipientsUseCase = FakeFindSubscriptionRecipientsUseCase()

    @Bean
    fun resolveChannelBindingUseCase(): FakeResolveChannelBindingUseCase = FakeResolveChannelBindingUseCase()

    @Bean
    fun findUsersByRoleUseCase(): FakeFindUsersByRoleUseCase = FakeFindUsersByRoleUseCase()
}
