import { http } from '@/utils/http'
import type { CommonResult } from '@/types/common'

/** 秒杀下单参数 */
export type FlashPromotionOrderParam = {
  /** 秒杀关联ID */
  flashPromotionRelationId: number
  /** 收货地址ID */
  memberReceiveAddressId: number
  /** 手机号 */
  phone: string
}

/** 秒杀下单 */
export const generateFlashPromotionOrderAPI = (data: FlashPromotionOrderParam) => {
  return http<CommonResult<unknown>>({
    method: 'POST',
    url: '/flashPromotion/order/generate',
    data,
  })
}
