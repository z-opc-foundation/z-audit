import { useMemo, useState } from 'react'
import { Button, Card, Form, Input, InputNumber, Space, Statistic, Table, Typography, message } from 'antd'
import { FileSearchOutlined, ReloadOutlined } from '@ant-design/icons'
import { auditApi } from '../services/api'

const { Text } = Typography

/** 审计事件查询：GET /z-team/api/audit/events（多条件过滤，列从数据动态推导）。 */
export default function EventsPage() {
    const [form] = Form.useForm()
    const [loading, setLoading] = useState(false)
    const [result, setResult] = useState(null)

    const runQuery = async (values) => {
        setLoading(true)
        try {
            const params = {}
            Object.entries(values || {}).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') params[k] = v })
            const { data } = await auditApi.listEvents(params)
            if (!data.ok) throw new Error(data.message || '查询失败')
            setResult(data)
        } catch (e) {
            message.error(`查询失败：${e.response?.status || e.message}`)
        } finally {
            setLoading(false)
        }
    }

    const columns = useMemo(() => {
        const rows = result?.events || []
        if (!rows.length) return []
        const sample = rows[0]
        return Object.keys(sample).map((k) => ({
            title: k,
            dataIndex: k,
            ellipsis: true,
            render: (v) => (v === null || v === undefined) ? '-'
                : typeof v === 'object' ? <Text code style={{ fontSize: 12 }}>{JSON.stringify(v)}</Text> : String(v),
        }))
    }, [result])

    return (
        <div>
            <Card title="审计事件查询" style={{ marginBottom: 16 }}>
                <Form form={form} layout="inline" onFinish={runQuery}>
                    <Form.Item name="tenantId"><Input placeholder="tenantId" style={{ width: 140 }} /></Form.Item>
                    <Form.Item name="operatorId"><Input placeholder="operatorId" style={{ width: 140 }} /></Form.Item>
                    <Form.Item name="action"><Input placeholder="action" style={{ width: 140 }} /></Form.Item>
                    <Form.Item name="traceId"><Input placeholder="traceId" style={{ width: 200 }} /></Form.Item>
                    <Form.Item name="targetType"><Input placeholder="targetType" style={{ width: 140 }} /></Form.Item>
                    <Form.Item name="targetId"><Input placeholder="targetId" style={{ width: 160 }} /></Form.Item>
                    <Form.Item name="limit" initialValue={100}>
                        <InputNumber min={1} max={1000} placeholder="limit" style={{ width: 90 }} />
                    </Form.Item>
                    <Form.Item>
                        <Space>
                            <Button type="primary" icon={<FileSearchOutlined />} loading={loading} htmlType="submit">查询</Button>
                            <Button icon={<ReloadOutlined />} onClick={() => { form.resetFields(); runQuery({}) }}>清空</Button>
                        </Space>
                    </Form.Item>
                </Form>
            </Card>

            {result && (
                <Card title="查询结果" extra={<Statistic title="总数" value={result.total} valueStyle={{ fontSize: 20 }} />}>
                    <Table
                        rowKey={(_, i) => i}
                        size="small"
                        columns={columns}
                        dataSource={result.events}
                        scroll={{ x: 'max-content' }}
                        pagination={{ pageSize: 20, showSizeChanger: false }}
                    />
                </Card>
            )}
        </div>
    )
}
