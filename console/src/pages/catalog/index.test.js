const fs = require('fs');
const path = require('path');

describe('product catalog UI contract', () => {
  it('registers a visible product catalog page and API endpoints', () => {
    const routes = fs.readFileSync(path.join(__dirname, '../../../config/routes.ts'), 'utf8');
    const app = fs.readFileSync(path.join(__dirname, '../../app.tsx'), 'utf8');
    const service = fs.readFileSync(path.join(__dirname, '../../services/catalog.ts'), 'utf8');
    const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
    const drawer = fs.readFileSync(path.join(__dirname, 'CatalogEditDrawer.tsx'), 'utf8');

    expect(routes).toContain("path: '/catalog'");
    expect(app).toContain('商品资料库');
    expect(service).toContain("PAGE = '/api/productCatalog/page'");
    expect(service).toContain("DETAIL = '/api/productCatalog/'");
    expect(page).toContain('CatalogEditDrawer');
    expect(drawer).toContain('编辑商品资料');
    expect(page).toContain('图片数量');
    expect(page).toContain('manualOverride');
  });
});
