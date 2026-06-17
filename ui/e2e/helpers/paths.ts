import { fileURLToPath } from 'url';
import path from 'path';

// ESM-compatible __dirname for the e2e/ directory
const __filename = fileURLToPath(import.meta.url);
const E2E_DIR = path.dirname(path.dirname(__filename)); // e2e/helpers/ → e2e/

export const AUTH_DIR       = path.join(E2E_DIR, '.auth');
export const adminAuthFile   = path.join(E2E_DIR, '.auth', 'admin.json');
export const engineerAuthFile = path.join(E2E_DIR, '.auth', 'engineer.json');
export const viewerAuthFile  = path.join(E2E_DIR, '.auth', 'viewer.json');

// UI served by Vite dev server; API served by Nginx (Docker)
export const UI_URL  = 'http://localhost:5173';
export const API_URL = 'http://localhost:3000';
