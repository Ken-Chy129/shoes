import React, {useEffect, useState, useRef, useCallback, useMemo} from "react";
import {Modal, Table, Input, Select, Space, Button, Switch, Tooltip} from "antd";
import {ReloadOutlined, DownloadOutlined} from "@ant-design/icons";
import {doGetRequest} from "@/util/http";
import {TASK_API} from "@/services/task";

interface TaskItemRecord {
    id: string;
    taskId: string;
    round: number;
    brand: string;
    title: string;
    listingId: string;
    productId: string;
    styleId: string;
    size: string;
    euSize: string;
    currentPrice: number;
    targetPrice: number;
    lowestPrice: number;
    flexLowestPrice: number;
    listingQuantity: number;
    poisonPrice: number;
    poison35Price: number;
    profit35: number;
    profitRate35: number;
    operateResult: string;
    operateTime: string;
    orderNumber: string;
    orderStatus: string;
    currencyCode: string;
    salePrice: number;
    payoutAmount: number;
    soldOn: string;
}

interface TaskItemModalProps {
    visible: boolean;
    taskId: string | null;
    onClose: () => void;
    defaultAutoRefresh?: boolean;
    taskType?: string;
    params?: string;
    attributes?: string;
    round?: number;
}

const TaskItemModal: React.FC<TaskItemModalProps> = ({visible, taskId, onClose, defaultAutoRefresh = false, taskType, params, attributes, round}) => {
    const [taskItems, setTaskItems] = useState<TaskItemRecord[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(20);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);
    const [autoRefresh, setAutoRefresh] = useState(defaultAutoRefresh);

    // 筛选条件
    const [filterRound, setFilterRound] = useState<string>('');
    const [filterOperateResult, setFilterOperateResult] = useState<string>('');
    const [filterStyleId, setFilterStyleId] = useState<string>('');
    const [styleIdInput, setStyleIdInput] = useState<string>('');
    const [operateResultOptions, setOperateResultOptions] = useState<{label: string, value: string}[]>([]);
    const debounceRef = useRef<NodeJS.Timeout | null>(null);

    // 用于存储定时器
    const timerRef = useRef<NodeJS.Timeout | null>(null);

    useEffect(() => {
        if (visible && taskId) {
            queryTaskItems();
            queryOperateResults();
            setAutoRefresh(defaultAutoRefresh);
        }
    }, [visible, taskId, pageIndex, pageSize, filterRound, filterOperateResult, filterStyleId]);

    const queryOperateResults = () => {
        if (!taskId) return;
        doGetRequest(TASK_API.TASK_ITEM_OPERATE_RESULTS, {taskId}, {
            onSuccess: res => {
                const options = [{label: '全部', value: ''}, ...(res.data || []).map((r: string) => ({label: r, value: r}))];
                setOperateResultOptions(options);
            }
        });
    }

    // 自动刷新逻辑
    useEffect(() => {
        if (autoRefresh && visible && taskId) {
            timerRef.current = setInterval(() => {
                queryTaskItems();
                queryOperateResults();
            }, 5000);
        } else {
            if (timerRef.current) {
                clearInterval(timerRef.current);
                timerRef.current = null;
            }
        }
        return () => {
            if (timerRef.current) {
                clearInterval(timerRef.current);
                timerRef.current = null;
            }
        };
    }, [autoRefresh, visible, taskId, pageIndex, pageSize, filterRound, filterOperateResult, filterStyleId]);

    const queryTaskItems = () => {
        if (!taskId) return;
        setLoading(true);
        const params: any = {taskId, pageIndex, pageSize};
        if (filterRound) params.round = filterRound;
        if (filterOperateResult) params.operateResult = filterOperateResult;
        if (filterStyleId) params.styleId = filterStyleId;

        doGetRequest(TASK_API.TASK_ITEM_PAGE, params, {
            onSuccess: res => {
                setTaskItems(res.data || []);
                setTotal(res.total || 0);
            },
            onFinally: () => setLoading(false)
        });
    }

    const handleStyleIdChange = (value: string) => {
        setStyleIdInput(value);
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => {
            setFilterStyleId(value);
            setPageIndex(1);
        }, 500);
    }

    const handleReset = () => {
        setFilterRound('');
        setFilterOperateResult('');
        setFilterStyleId('');
        setStyleIdInput('');
        setPageIndex(1);
    }

    const handleExport = () => {
        if (!taskId) return;
        const params = new URLSearchParams();
        params.append('taskId', taskId);
        if (filterRound) params.append('round', filterRound);
        if (filterOperateResult) params.append('operateResult', filterOperateResult);
        if (filterStyleId) params.append('styleId', filterStyleId);
        window.open(`${TASK_API.TASK_ITEM_EXPORT}?${params.toString()}`, '_blank');
    }

    const formatOrderMoney = (value: number, currencyCode: string) => {
        if (value === null || value === undefined) return '-';
        const prefix = currencyCode === 'USD' ? '$' : (currencyCode ? `${currencyCode} ` : '');
        return `${prefix}${value}`;
    };

    const purchaseOperation = useMemo(() => {
        if (taskType !== 'purchase' || !params) return undefined;
        try {
            return JSON.parse(params).operation as 'bids' | 'orders' | 'history' | 'create_bids' | undefined;
        } catch {
            return undefined;
        }
    }, [taskType, params]);

    const productColumns = [
        {
            title: '轮次',
            dataIndex: 'round',
            key: 'round',
            width: 50,
        },
        {
            title: '品牌',
            dataIndex: 'brand',
            key: 'brand',
            width: 80,
            ellipsis: true,
        },
        {
            title: '标题',
            dataIndex: 'title',
            key: 'title',
            width: 160,
            ellipsis: true,
        },
        {
            title: '货号',
            dataIndex: 'styleId',
            key: 'styleId',
            width: 110,
        },
        {
            title: '尺码',
            dataIndex: 'size',
            key: 'size',
            width: 50,
        },
        {
            title: 'EU码',
            dataIndex: 'euSize',
            key: 'euSize',
            width: 50,
        },
        {
            title: '当前价',
            dataIndex: 'currentPrice',
            key: 'currentPrice',
            width: 70,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: '最低价',
            dataIndex: 'lowestPrice',
            key: 'lowestPrice',
            width: 70,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: '毒价格',
            dataIndex: 'poisonPrice',
            key: 'poisonPrice',
            width: 70,
            render: (price: number) => price ? `¥${price}` : '-',
        },
        {
            title: '3.5价格',
            dataIndex: 'poison35Price',
            key: 'poison35Price',
            width: 70,
            render: (price: number) => price ? `¥${price}` : '-',
        },
        {
            title: '利润',
            dataIndex: 'profit35',
            key: 'profit35',
            width: 70,
            render: (profit: number) => {
                if (profit === null || profit === undefined) return '-';
                const color = profit >= 0 ? 'green' : 'red';
                return <span style={{color}}>${profit}</span>;
            },
        },
        {
            title: '利润率',
            dataIndex: 'profitRate35',
            key: 'profitRate35',
            width: 70,
            render: (rate: number) => {
                if (rate === null || rate === undefined) return '-';
                const color = rate >= 0 ? 'green' : 'red';
                return <span style={{color}}>{(rate * 100).toFixed(2)}%</span>;
            },
        },
        {
            title: '操作结果',
            dataIndex: 'operateResult',
            key: 'operateResult',
            width: 140,
            ellipsis: true,
        },
        {
            title: '操作时间',
            dataIndex: 'operateTime',
            key: 'operateTime',
            width: 150,
        },
    ];

    const orderColumns = [
        {title: '商品id', dataIndex: 'productId', key: 'productId', width: 180, ellipsis: true},
        {title: 'id', dataIndex: 'listingId', key: 'listingId', width: 170, ellipsis: true},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 220, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 130},
        {title: '尺码', dataIndex: 'size', key: 'size', width: 70},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 70},
        {title: '订单号', dataIndex: 'orderNumber', key: 'orderNumber', width: 150},
        {
            title: 'StockX出售价格', dataIndex: 'salePrice', key: 'salePrice', width: 120,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {
            title: '最终货款', dataIndex: 'payoutAmount', key: 'payoutAmount', width: 110,
            render: (value: number, record: TaskItemRecord) => record.orderStatus === '销售完成'
                ? formatOrderMoney(value, record.currencyCode) : '-',
        },
        {
            title: '得物价格', dataIndex: 'poisonPrice', key: 'poisonPrice', width: 100,
            render: (value: number, record: TaskItemRecord) => record.orderStatus === '待处理'
                && value !== null && value !== undefined ? `¥${value}` : '-',
        },
        {title: '出售日期', dataIndex: 'soldOn', key: 'soldOn', width: 160},
        {
            title: '截止日期', dataIndex: 'operateTime', key: 'shipByDate', width: 160,
            render: (value: string, record: TaskItemRecord) => record.orderStatus === '待处理' ? value : '-',
        },
        {title: '订单状态', dataIndex: 'orderStatus', key: 'orderStatus', width: 100},
        {
            title: '延期状态', dataIndex: 'operateResult', key: 'extensionStatus', width: 100,
            render: (value: string, record: TaskItemRecord) => record.orderStatus === '待处理' ? value : '-',
        },
    ];

    const purchaseBidColumns = [
        {title: purchaseOperation === 'create_bids' ? '批次 ID' : '出价 ID', dataIndex: 'listingId', key: 'listingId', width: 180, ellipsis: true},
        {title: 'variantId', dataIndex: 'productId', key: 'productId', width: 180, ellipsis: true},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 240, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 130},
        {title: 'US码', dataIndex: 'size', key: 'size', width: 75},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 75},
        {
            title: '出价金额', dataIndex: 'currentPrice', key: 'currentPrice', width: 105,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {title: '状态', dataIndex: 'orderStatus', key: 'orderStatus', width: 100},
        {title: '操作结果', dataIndex: 'operateResult', key: 'operateResult', width: 180, ellipsis: true},
        {title: '创建时间', dataIndex: 'operateTime', key: 'operateTime', width: 165},
    ];

    const purchaseOrderColumns = [
        {title: '订单号', dataIndex: 'orderNumber', key: 'orderNumber', width: 155},
        {title: 'chainId', dataIndex: 'listingId', key: 'listingId', width: 180, ellipsis: true},
        {title: 'variantId', dataIndex: 'productId', key: 'productId', width: 180, ellipsis: true},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 240, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 130},
        {title: 'US码', dataIndex: 'size', key: 'size', width: 75},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 75},
        {
            title: '购买价格', dataIndex: 'salePrice', key: 'salePrice', width: 105,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {title: '订单状态', dataIndex: 'orderStatus', key: 'orderStatus', width: 120},
        {title: '购买时间', dataIndex: 'soldOn', key: 'soldOn', width: 165},
    ];

    const modelSearchColumns = [
        {title: 'variantId', dataIndex: 'productId', key: 'productId', width: 190, ellipsis: true},
        {title: '品牌', dataIndex: 'brand', key: 'brand', width: 90, ellipsis: true},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 220, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 130},
        {title: 'US码', dataIndex: 'size', key: 'size', width: 75},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 75},
        {
            title: '现货最低价', dataIndex: 'lowestPrice', key: 'lowestPrice', width: 105,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: 'Flex最低价', dataIndex: 'flexLowestPrice', key: 'flexLowestPrice', width: 105,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: '目标上架价', dataIndex: 'targetPrice', key: 'targetPrice', width: 105,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {title: '上架数量', dataIndex: 'listingQuantity', key: 'listingQuantity', width: 90, render: (v: number) => v || '-'},
        {title: '操作结果', dataIndex: 'operateResult', key: 'operateResult', width: 180, ellipsis: true},
        {title: '操作时间', dataIndex: 'operateTime', key: 'operateTime', width: 165},
    ];

    const shippingExtensionColumns = [
        {title: '订单号', dataIndex: 'orderNumber', key: 'orderNumber', width: 160},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 260, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 130},
        {title: '尺码', dataIndex: 'size', key: 'size', width: 80},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 80},
        {title: 'Ask ID', dataIndex: 'listingId', key: 'listingId', width: 170, ellipsis: true},
        {title: '执行结果', dataIndex: 'operateResult', key: 'operateResult', width: 190, ellipsis: true},
        {title: '执行时间', dataIndex: 'operateTime', key: 'operateTime', width: 170},
    ];

    const replenishmentColumns = [
        {title: '订单号', dataIndex: 'orderNumber', key: 'orderNumber', width: 155},
        {title: 'variantId', dataIndex: 'productId', key: 'productId', width: 180, ellipsis: true},
        {title: '产品名称', dataIndex: 'title', key: 'title', width: 220, ellipsis: true},
        {title: '货号', dataIndex: 'styleId', key: 'styleId', width: 125},
        {title: 'US码', dataIndex: 'size', key: 'size', width: 70},
        {title: 'EU码', dataIndex: 'euSize', key: 'euSize', width: 70},
        {
            title: '原售出价', dataIndex: 'salePrice', key: 'salePrice', width: 95,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {
            title: '当前最低价', dataIndex: 'lowestPrice', key: 'lowestPrice', width: 100,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {
            title: '补单价', dataIndex: 'targetPrice', key: 'targetPrice', width: 85,
            render: (value: number, record: TaskItemRecord) => formatOrderMoney(value, record.currencyCode),
        },
        {
            title: '最新得物价', dataIndex: 'poisonPrice', key: 'poisonPrice', width: 105,
            render: (value: number) => value !== null && value !== undefined ? `¥${value}` : '-',
        },
        {
            title: '预计利润', dataIndex: 'profit35', key: 'profit35', width: 90,
            render: (value: number) => value !== null && value !== undefined
                ? <span style={{color: value >= 0 ? 'green' : 'red'}}>{`¥${value}`}</span> : '-',
        },
        {title: '出售时间', dataIndex: 'soldOn', key: 'soldOn', width: 165},
        {title: '执行结果', dataIndex: 'operateResult', key: 'operateResult', width: 170, ellipsis: true},
    ];

    const columns = taskType === 'purchase'
        ? (purchaseOperation === 'bids' || purchaseOperation === 'create_bids'
            ? purchaseBidColumns : purchaseOrderColumns)
        : taskType === 'fetch_orders'
        ? orderColumns
        : taskType === 'extend_shipping'
            ? shippingExtensionColumns
            : taskType === 'replenishment'
                ? replenishmentColumns
                : taskType === 'model_search' ? modelSearchColumns : productColumns;

    const handleClose = () => {
        setPageIndex(1);
        setTaskItems([]);
        setTotal(0);
        setFilterRound('');
        setFilterOperateResult('');
        setFilterStyleId('');
        setStyleIdInput('');
        setAutoRefresh(false);
        onClose();
    }

    return (
        <Modal
            title={taskType === 'extend_shipping' ? '订单延期明细'
                : taskType === 'replenishment' ? '补单明细'
                    : taskType === 'purchase' ? '购买明细' : '任务明细'}
            open={visible}
            onCancel={handleClose}
            footer={null}
            width={taskType === 'fetch_orders' || taskType === 'purchase' ? 1400 : 1200}
        >
            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16}}>
                <Space wrap>
                    {taskType !== 'fetch_orders' && taskType !== 'extend_shipping' && taskType !== 'replenishment' && <Input
                        placeholder="轮次"
                        value={filterRound}
                        onChange={e => { setFilterRound(e.target.value); setPageIndex(1); }}
                        style={{width: 80}}
                        type="number"
                    />}
                    <Select
                        placeholder="操作结果"
                        value={filterOperateResult}
                        onChange={v => { setFilterOperateResult(v); setPageIndex(1); }}
                        style={{width: 180}}
                        options={operateResultOptions}
                        allowClear
                    />
                    <Input
                        placeholder="货号"
                        value={styleIdInput}
                        onChange={e => handleStyleIdChange(e.target.value)}
                        style={{width: 130}}
                    />
                    <Button icon={<ReloadOutlined/>} onClick={handleReset}>
                        重置
                    </Button>
                    {taskType !== 'extend_shipping' && <Button icon={<DownloadOutlined/>} onClick={handleExport}>
                        导出
                    </Button>}
                </Space>
                <Space>
                    {(taskType === 'listing' || taskType === 'model_search') && attributes && (() => {
                        try {
                            const attrs = JSON.parse(attributes);
                            const tip = `${attrs.detail || ''} | 搜索进度 ${attrs.progress ?? 0}%`;
                            return <Tooltip title={tip}>
                                <span style={{cursor: 'pointer', color: '#1677ff', fontWeight: 500}}>
                                    {taskType === 'listing' ? `已上架 ${attrs.listed ?? 0}` : `已处理 ${attrs.processed ?? attrs.current ?? 0}/${attrs.total ?? 0}`}
                                    {taskType === 'listing' && attrs.processed != null ? ` | 已处理 ${attrs.processed}` : ''}
                                    {attrs.keywordTotal != null ? ` | 词 ${attrs.keywordIdx ?? 0}/${attrs.keywordTotal}` : ''}
                                </span>
                            </Tooltip>;
                        } catch { return null; }
                    })()}
                    {taskType === 'price_down' && round != null && (
                        <span style={{color: '#1677ff', fontWeight: 500}}>第{round}轮</span>
                    )}
                    {taskType === 'extend_shipping' && attributes && (() => {
                        try {
                            const attrs = JSON.parse(attributes);
                            return <span style={{color: '#1677ff', fontWeight: 500}}>
                                扫描 {attrs.scanned ?? 0} | 成功 {attrs.extended ?? 0} | 失败 {attrs.failed ?? 0}
                            </span>;
                        } catch { return null; }
                    })()}
                    {taskType === 'replenishment' && attributes && (() => {
                        try {
                            const attrs = JSON.parse(attributes);
                            return <span style={{color: '#1677ff', fontWeight: 500}}>
                                售出 {attrs.scanned ?? 0} | 符合利润 {attrs.profitable ?? 0}笔 | 提交补单 {attrs.listingQuantity ?? 0}件
                            </span>;
                        } catch { return null; }
                    })()}
                    <span>自动刷新</span>
                    <Switch checked={autoRefresh} onChange={setAutoRefresh}/>
                </Space>
            </div>
            <Table
                columns={columns}
                dataSource={taskItems}
                loading={loading}
                scroll={{x: taskType === 'fetch_orders' ? 1700
                    : taskType === 'purchase' ? 1500
                    : taskType === 'model_search' ? 1450
                        : taskType === 'replenishment' ? 1550 : 1130}}
                pagination={{
                    current: pageIndex,
                    pageSize: pageSize,
                    total: total,
                    showSizeChanger: true,
                    showTotal: (total) => `共 ${total} 条`,
                    onChange: (current, size) => {
                        setPageIndex(current);
                        setPageSize(size);
                    }
                }}
                rowKey="id"
                size="small"
            />
        </Modal>
    );
}

export default TaskItemModal;
