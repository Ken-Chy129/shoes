export default function access(initialState: { currentUser?: { username: string } } | undefined) {
  const { currentUser } = initialState || {};

  return {
    isLogin: !!currentUser,
  };
} 