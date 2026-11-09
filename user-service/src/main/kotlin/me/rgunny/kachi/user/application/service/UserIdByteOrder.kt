package me.rgunny.kachi.user.application.service

import me.rgunny.kachi.user.domain.UserId

/**
 * userId를 부호 없는 16바이트 순으로 비교하는 Comparator.
 *
 * 상위 64비트, 하위 64비트를 차례로 부호 없는 정수로 비교한다.
 */
object UserIdByteOrder : Comparator<UserId> {

    override fun compare(left: UserId, right: UserId): Int {
        val high = java.lang.Long.compareUnsigned(left.value.mostSignificantBits, right.value.mostSignificantBits)
        if (high != 0) return high

        return java.lang.Long.compareUnsigned(left.value.leastSignificantBits, right.value.leastSignificantBits)
    }
}
