import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { message } from 'antd';
import { history } from 'umi';
import styles from './index.less';

export default function LoginPage() {
  const handleSubmit = async (values: any) => {
    const { username, password } = values;
    try {
      // TODO: 调用登录接口
      message.success('登录成功！');
      history.push('/price');
    } catch (error) {
      message.error('登录失败，请重试！');
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
          placeholder="用户名"
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
          placeholder="密码"
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