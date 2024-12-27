import React from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card } from 'antd';

const PricePage: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '价格查询',
      }}
    >
      <Card>
        <div>价格查询页面内容</div>
      </Card>
    </PageContainer>
  );
};

export default PricePage; 