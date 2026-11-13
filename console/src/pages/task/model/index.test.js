const fs = require('fs');
const path = require('path');

describe('special model delete-all UI contract', () => {
  const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
  const service = fs.readFileSync(path.join(__dirname, '../../../services/shoes.ts'), 'utf8');

  it('uses a dedicated bulk-delete endpoint without pagination or filters', () => {
    expect(service).toContain("DELETE_ALL_SPECIAL_MODELS = '/api/setting/deleteAllSpecialModels'");
    expect(page).toContain('doDeleteRequest(SETTING_API.DELETE_ALL_SPECIAL_MODELS, {},');
  });

  it('requires explicit confirmation of the full, irreversible scope', () => {
    expect(page).toContain('确认删除全部特殊货号？');
    expect(page).toContain('不受当前筛选条件和分页影响');
    expect(page).toContain('必爬、禁爬、不比价');
    expect(page).toContain('无法撤销');
    expect(page).toContain('onOk={handleDeleteAll}');
    expect(page).toContain('okButtonProps={{danger: true}}');
    expect(page).toContain('confirmLoading={deletingAll}');
  });

  it('keeps errors visible and refreshes from the first page after success', () => {
    expect(page).toContain("onError: res => message.error(res.errorMsg || '全部删除失败')");
    expect(page).toContain('onFinally: () => setDeletingAll(false)');
    expect(page).toContain('if (pageIndex === 1) queryList();');
    expect(page).toContain('else setPageIndex(1);');
  });
});
