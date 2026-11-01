import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    resolve: {dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
        alias: process.env.LOCAL_SIBLINGS === '1' ? {'@yuku123/z-audit-component': '../z-audit-component/src'} : {}},
    server: {
        port: 3040,
        fs: {allow: ['..']},
        proxy: {'/z-team': {target: 'http://localhost:8888', changeOrigin: true}, '/api': {target: 'http://localhost:8888', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}},
    },
})
