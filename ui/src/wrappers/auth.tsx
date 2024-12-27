import { Navigate, Outlet, useModel } from '@umijs/max';

const AuthWrapper: React.FC = () => {
  const { initialState } = useModel('@@initialState');

  // 如果没有用户信息且没有 token，重定向到登录页
  if (!initialState?.currentUser && !localStorage.getItem('token')) {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
};

export default AuthWrapper; 