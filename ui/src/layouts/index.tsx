import { ProLayout } from '@ant-design/pro-components';
import { Link, Outlet, useModel } from '@umijs/max';
import { MenuDataItem } from '@ant-design/pro-components';
import {SearchOutlined, EditOutlined, SettingOutlined} from '@ant-design/icons';

// 定义菜单配置
const menuData: MenuDataItem[] = [
  {
    path: '/price',
    name: '价格查询',
    icon: <SearchOutlined />,
  },
  {
    path: '/price-update',
    name: '价格更新',
    icon: <EditOutlined />,
  },
  {
    path: '/setting',
    name: '配置',
    icon: <SettingOutlined />,
  },
  {
    path: '/task',
    name: '任务',
    icon: <SettingOutlined />,
  },
];

export default function Layout() {
  const { initialState } = useModel('@@initialState');

  return (
    <ProLayout
      logo="https://img.alicdn.com/tfs/TB1YHEpwUT1gK0jSZFhXXaAtVXa-28-27.svg"
      title="鞋子管理系统"
      layout="side"
      navTheme="light"
      contentWidth="Fixed"
      fixedHeader
      fixSiderbar
      menu={{
        locale: false,
      }}
      route={{
        routes: menuData,
      }}
      menuItemRender={(item, dom) => (
        <Link to={item.path || '/'}>{dom}</Link>
      )}
      token={{
        header: {
          heightLayoutHeader: 48,
        },
        sider: {
          colorMenuBackground: '#fff',
          colorTextMenu: '#595959',
          colorTextMenuSelected: '#1890ff',
          colorBgMenuItemSelected: '#e6f7ff',
        },
      }}
    >
      <Outlet />
    </ProLayout>
  );
}
