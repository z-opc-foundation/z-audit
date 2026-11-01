/**
 * z-audit API client：走 /z-team/api/audit/** 面（审计事件 + 哈希链 + 追踪）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zaudit_token'})

export default request

export function configureAudit(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const auditApi = {
    listEvents: (params) => request.get('/z-team/api/audit/events', {params}),
    chainLatest: () => request.get('/z-team/api/audit/chain/latest'),
    chainVerify: (params) => request.get('/z-team/api/audit/chain/verify', {params}),
    getTrace: (traceId) => request.get(`/z-team/api/audit/trace/${encodeURIComponent(traceId)}`),
    health: () => request.get('/api/audit/health'),
}
