import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {fileURLToPath} from 'node:url'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    resolve: {
        dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
        alias: process.env.LOCAL_SIBLINGS === '1' ? [
            {find: /^@yuku123\/z-audit-component\/pages$/, replacement: fileURLToPath(new URL('../z-audit-component/src/pages.js', import.meta.url))},
            {find: /^@yuku123\/z-audit-component$/, replacement: fileURLToPath(new URL('../z-audit-component/src/index.js', import.meta.url))},
            {find: /^@yuku123\/z-frontend-common\/ui$/, replacement: fileURLToPath(new URL('../../../z-frontend-common/src/components/ui/index.js', import.meta.url))},
            {find: /^@yuku123\/z-frontend-common$/, replacement: fileURLToPath(new URL('../../../z-frontend-common/src/index.js', import.meta.url))},
        ] : {},
    },
    server: {
        port: 3040,
        fs: {allow: [fileURLToPath(new URL(process.env.LOCAL_SIBLINGS === '1' ? '../../../' : '../', import.meta.url))]},
        proxy: {'/z-team': {target: 'http://localhost:8888', changeOrigin: true}, '/api': {target: 'http://localhost:8888', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}},
    },
})
