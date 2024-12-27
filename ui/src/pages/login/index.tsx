import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { message } from 'antd';
import { history, useModel } from '@umijs/max';
import styles from './index.less';
import { login, fetchUserInfo } from '@/services/user';

export default function LoginPage() {
  const { refresh } = useModel('@@initialState');

  const handleSubmit = async (values: any) => {
    const { username, password } = values;
    try {
      const res = await login({ username, password });
      
      if (res.code === 200) {
        localStorage.setItem('token', res.data);
        
        try {
          const userInfo = await fetchUserInfo();
          if (userInfo) {
            message.success('登录成功！');
            await refresh();
            queueMicrotask(() => {
              history.replace('/price');
            });
          }
        } catch (error) {
          message.error('获取用户信息失败');
          localStorage.removeItem('token');
        }
      } else {
        message.error(res.msg || '登录失败，请重试！');
      }
    } catch (error: any) {
      message.error(error.response?.data?.msg || '登录失败，请检查网络连接！');
    }
  };

  return (
    <div className={styles.container}>
      <LoginForm
        title="鞋子管理系统"
        subTitle="价格查询与更新平台"
        onFinish={handleSubmit}
      >
        <ProFormText
          name="username"
          fieldProps={{
            size: 'large',
            prefix: <UserOutlined />,
          }}
          placeholder="用户名: admin"
          rules={[
            {
              required: true,
              message: '请输入用户名!',
            },
          ]}
        />
        <ProFormText.Password
          name="password"
          fieldProps={{
            size: 'large',
            prefix: <LockOutlined />,
          }}
          placeholder="密码: admin"
          rules={[
            {
              required: true,
              message: '请输入密码！',
            },
          ]}
        />
      </LoginForm>
    </div>
  );
} 