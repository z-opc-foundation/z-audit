import { HomeOutlined, FileSearchOutlined, LinkOutlined, ReadOutlined } from '@ant-design/icons'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import ComponentDocPage from './pages/ComponentDoc.jsx'
import EventsPage from './pages/EventsPage.jsx'
import ChainPage from './pages/ChainPage.jsx'

/** 菜单 + 路由清单（lead 005 §8.3：主壳按本清单重组）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-audit 审计', short: 'z-audit' }

export const menuItems = [
    { key: '/z-audit/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-audit/events', label: '事件查询', icon: <FileSearchOutlined /> },
    { key: '/z-audit/chain', label: '哈希链核验', icon: <LinkOutlined /> },
    { key: '/z-audit/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-audit/home', Component: HomePage },
    { path: '/z-audit/events', Component: EventsPage },
    { path: '/z-audit/chain', Component: ChainPage },
    { path: '/z-audit/docs', Component: ComponentDocPage },
]

export { HomePage, LoginPage, ComponentDocPage }
