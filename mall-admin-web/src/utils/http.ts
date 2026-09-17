import { useUserStore } from '@/stores/user'
import type { CommonResult } from '@/types/common'
import axios, { type InternalAxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

// 创建axios实例
const http = axios.create({
  baseURL: import.meta.env.VITE_BASE_SERVER_URL,
  timeout: 5000,
})

// axios请求拦截器
http.interceptors.request.use(
  config => {
    //从pinia获取token
    const userStore = useUserStore()
    const token = userStore.userInfo.token
    if (token) {
      config.headers.Authorization = token
    }
    return config
  },
  e => Promise.reject(e),
)

// axios响应拦截器
http.interceptors.response.use(
  async response => {
    const res: CommonResult<unknown> = response.data
    if (res.code !== 200) {
      // 401:未登录——先尝试用refreshToken自动换发，换发成功则用新token重试原请求
      if (res.code === 401) {
        const originalRequest = response.config as InternalAxiosRequestConfig & { _retry?: boolean }
        // _retry标记：重试过的请求再401就不再换发，防止无限循环
        if (!originalRequest._retry) {
          originalRequest._retry = true
          try {
            const userStore = useUserStore()
            const refreshToken = userStore.userInfo.refreshToken
            if (refreshToken) {
              // 用裸axios调auth服务换发（不走本实例拦截器，避免递归）
              const refreshRes = await axios.post(
                `${import.meta.env.VITE_AUTH_SERVER_URL}/auth/refresh`,
                null,
                { params: { clientId: 'admin-app', refreshToken } },
              )
              if (refreshRes.data.code === 200) {
                const newData = refreshRes.data.data
                // 保存新token并重试原请求（用户无感）
                userStore.userInfo.token = newData.tokenHead + newData.token
                userStore.userInfo.refreshToken = newData.refreshToken
                originalRequest.headers.Authorization = userStore.userInfo.token
                return http(originalRequest)
              }
            }
          } catch (e) {
            console.log('自动换发token失败', e)
          }
        }
        // 换发失败或重试后仍401：弹登出确认
        ElMessageBox.confirm('你已被登出，可以取消继续留在该页面，或者重新登录', '确定登出', {
          confirmButtonText: '重新登录',
          cancelButtonText: '取消',
          type: 'warning',
        }).then(() => {
          const userStore = useUserStore()
          userStore.fedLogout()
          // 为了重新实例化vue-router对象 避免bug
          location.reload()
        })
        return Promise.reject('error')
      }
      // code为非200是抛错，这里统一处理提示信息
      ElMessage({
        message: res.message,
        type: 'error',
        duration: 3 * 1000,
      })
      return Promise.reject('error')
    } else {
      // 返回响应JSON中的data属性，不包括message和code
      return response.data
    }
  },
  error => {
    // 全局处理异常请求
    console.log('error' + error)
    ElMessage({
      message: error.message,
      type: 'error',
      duration: 3 * 1000,
    })
    return Promise.reject(error)
  },
)

export default http
