import { useEffect, useState } from 'react'
import { Button, Card, Descriptions, Form, Input, Result, Space, Tag, Typography, message } from 'antd'
import { LinkOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { auditApi } from '../services/api'

const { Text } = Typography

/** 哈希链核验：GET /chain/latest + GET /chain/verify（tenantId/from/to）。 */
export default function ChainPage() {
    const [latest, setLatest] = useState(null)
    const [loading, setLoading] = useState(false)
    const [verifyResult, setVerifyResult] = useState(null)

    const loadLatest = async () => {
        try {
            const { data } = await auditApi.chainLatest()
            setLatest(data)
        } catch (e) {
            message.error(`读取链头失败：${e.response?.status || e.message}`)
        }
    }

    useEffect(() => { loadLatest() }, [])

    const runVerify = async (values) => {
        setLoading(true)
        try {
            const params = {}
            Object.entries(values || {}).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') params[k] = v })
            const { data } = await auditApi.chainVerify(params)
            setVerifyResult(data)
        } catch (e) {
            message.error(`核验失败：${e.response?.status || e.message}`)
        } finally {
            setLoading(false)
        }
    }

    return (
        <div>
            <Card title="链头状态" style={{ marginBottom: 16 }} extra={<Button icon={<LinkOutlined />} onClick={loadLatest}>刷新</Button>}>
                {latest ? (
                    <Descriptions bordered column={2} size="small">
                        {Object.entries(latest)
                            .filter(([, v]) => v !== null && v !== undefined)
                            .map(([k, v]) => (
                                <Descriptions.Item key={k} label={k}>
                                    <Text copyable={typeof v === 'string' && v.length > 20} code={typeof v !== 'string'}>
                                        {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                                    </Text>
                                </Descriptions.Item>
                            ))}
                    </Descriptions>
                ) : (
                    <Text type="secondary">暂无链头数据（尚未 anchor 或服务未达）。</Text>
                )}
            </Card>

            <Card title="区间核验">
                <Form layout="inline" onFinish={runVerify}>
                    <Form.Item name="tenantId"><Input placeholder="tenantId" style={{ width: 160 }} /></Form.Item>
                    <Form.Item name="from"><Input placeholder="起始（时间/序号）" style={{ width: 180 }} /></Form.Item>
                    <Form.Item name="to"><Input placeholder="截止（时间/序号）" style={{ width: 180 }} /></Form.Item>
                    <Form.Item>
                        <Button type="primary" icon={<SafetyCertificateOutlined />} loading={loading} htmlType="submit">核验</Button>
                    </Form.Item>
                </Form>

                {verifyResult && (
                    <div style={{ marginTop: 16 }}>
                        <Result
                            status={verifyResult.ok ? 'success' : 'error'}
                            title={verifyResult.ok ? '哈希链完整' : '哈希链异常'}
                            subTitle="ok=true 表示区间内链未被篡改"
                        />
                        <Space size={8} wrap>
                            {Object.entries(verifyResult)
                                .filter(([k]) => k !== 'ok')
                                .map(([k, v]) => (
                                    <Tag key={k}>{k}: {typeof v === 'object' ? JSON.stringify(v) : String(v)}</Tag>
                                ))}
                        </Space>
                    </div>
                )}
            </Card>
        </div>
    )
}
