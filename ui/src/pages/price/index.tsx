import { useRef, useState } from 'react';
import { ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { Button, Select, Space, Tag } from 'antd';
import { DownOutlined, UpOutlined } from '@ant-design/icons';
import { queryPriceList, querySizePriceList } from '@/services/price';
import type { PriceItem, SizePrice } from '@/services/types';

const PricePage = () => {
  const actionRef = useRef<ActionType>();
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);

  const columns: ProColumns[] = [
    {
      title: '商品图片',
      dataIndex: 'image',
      valueType: 'image',
      width: 100,
    },
    {
      title: '商品名称',
      dataIndex: 'name',
      ellipsis: true,
    },
    {
      title: '品牌',
      dataIndex: 'brand',
      valueType: 'select',
      valueEnum: {
        nike: { text: 'Nike' },
        adidas: { text: 'Adidas' },
        // ... 其他品牌
      },
    },
    {
      title: '货号',
      dataIndex: 'modelNumber',
    },
    {
      title: 'SPU ID',
      dataIndex: 'spuId',
    },
    {
      title: '操作',
      valueType: 'option',
      render: (_, record) => [
        <Button
          key="expand"
          type="link"
          onClick={() => {
            const key = record.id as string;
            setExpandedRowKeys(
              expandedRowKeys.includes(key)
                ? expandedRowKeys.filter((k) => k !== key)
                : [...expandedRowKeys, key]
            );
          }}
        >
          {expandedRowKeys.includes(record.id as string) ? (
            <>
              收起
              <UpOutlined />
            </>
          ) : (
            <>
              展开
              <DownOutlined />
            </>
          )}
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<PriceItem>
      columns={columns}
      actionRef={actionRef}
      cardBordered
      request={async (params) => {
        const { current, pageSize, ...rest } = params;
        const res = await queryPriceList({
          current,
          pageSize,
          ...rest,
        });
        return {
          data: res.data,
          success: res.success,
          total: res.total,
        };
      }}
      expandable={{
        expandedRowKeys,
        onExpandedRowsChange: (keys) => setExpandedRowKeys(keys as string[]),
        expandedRowRender: (record) => (
          <ProTable<SizePrice>
            columns={[
              { title: '尺码', dataIndex: 'size' },
              { 
                title: 'A平台价格', 
                dataIndex: 'priceA',
                valueType: 'money',
                fieldProps: { precision: 2 }
              },
              { 
                title: 'B平台价格', 
                dataIndex: 'priceB',
                valueType: 'money',
                fieldProps: { precision: 2 }
              },
              { 
                title: '利润', 
                dataIndex: 'profit',
                valueType: 'money',
                fieldProps: { precision: 2 }
              },
              { 
                title: '利润率', 
                dataIndex: 'profitRate',
                valueType: 'percent',
                fieldProps: { precision: 2 }
              },
            ]}
            headerTitle={false}
            search={false}
            options={false}
            pagination={false}
            request={async () => {
              const res = await querySizePriceList({
                id: record.id,
                priceType: record.priceType,
              });
              return {
                data: res.data,
                success: res.success,
              };
            }}
          />
        ),
      }}
      rowKey="id"
      search={{
        labelWidth: 'auto',
      }}
      form={{
        syncToUrl: true,
      }}
      pagination={{
        pageSize: 10,
      }}
      dateFormatter="string"
      headerTitle="商品价格列表"
      toolBarRender={() => [
        <Select
          key="priceType"
          placeholder="选择价格类型"
          style={{ width: 200 }}
          options={[
            { label: '普通价格', value: 'normal' },
            { label: '闪电价格', value: 'flash' },
            { label: '快速价格', value: 'fast' },
          ]}
        />,
      ]}
    />
  );
};

export default PricePage; 