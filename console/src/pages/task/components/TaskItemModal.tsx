import React, {useEffect, useState, useRef} from "react";
import {Modal, Table, Input, Select, Space, Button, Switch} from "antd";
import {SearchOutlined, ReloadOutlined} from "@ant-design/icons";
import {doGetRequest} from "@/util/http";
import {TASK_API} from "@/services/task";

interface TaskItemRecord {
    id: string;
    taskId: string;
    round: number;
    title: string;
    listingId: string;
    productId: string;
    styleId: string;
    size: string;
    euSize: string;
    currentPrice: number;
    lowestPrice: number;
    poisonPrice: number;
    poison35Price: number;
    profit35: number;
    profitRate35: number;
    operateResult: string;
    operateTime: string;
}

interface TaskItemModalProps {
    visible: boolean;
    taskId: string | null;
    onClose: () => void;
    defaultAutoRefresh?: boolean;
}

const OPERATE_RESULT_OPTIONS = [
    {label: '全部', value: ''},
    {label: '待处理', value: '待处理'},
    {label: '压价成功', value: '压价成功'},
    {label: '压价失败', value: '压价失败'},
    {label: '下架成功', value: '下架成功'},
    {label: '下架失败', value: '下架失败'},
    {label: '跳过', value: '跳过'},
    {label: '保持', value: '保持'},
    {label: '取消', value: '取消'},
];

const TaskItemModal: React.FC<TaskItemModalProps> = ({visible, taskId, onClose, defaultAutoRefresh = false}) => {
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

    // 用于存储定时器
    const timerRef = useRef<NodeJS.Timeout | null>(null);

    useEffect(() => {
        if (visible && taskId) {
            queryTaskItems();
            // 打开时根据 defaultAutoRefresh 设置自动刷新
            setAutoRefresh(defaultAutoRefresh);
        }
    }, [visible, taskId, pageIndex, pageSize]);

    // 自动刷新逻辑
    useEffect(() => {
        if (autoRefresh && visible && taskId) {
            timerRef.current = setInterval(() => {
                queryTaskItems();
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

    const handleSearch = () => {
        setPageIndex(1);
        queryTaskItems();
    }

    const handleReset = () => {
        setFilterRound('');
        setFilterOperateResult('');
        setFilterStyleId('');
        setPageIndex(1);
        // 重置后立即查询
        setTimeout(() => queryTaskItems(), 0);
    }

    const columns = [
        {
            title: '轮次',
            dataIndex: 'round',
            key: 'round',
            width: 50,
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

    const handleClose = () => {
        setPageIndex(1);
        setTaskItems([]);
        setTotal(0);
        setFilterRound('');
        setFilterOperateResult('');
        setFilterStyleId('');
        setAutoRefresh(false);
        onClose();
    }

    return (
        <Modal
            title="任务明细"
            open={visible}
            onCancel={handleClose}
            footer={null}
            width={1200}
        >
            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16}}>
                <Space wrap>
                    <Input
                        placeholder="轮次"
                        value={filterRound}
                        onChange={e => setFilterRound(e.target.value)}
                        style={{width: 80}}
                        type="number"
                    />
                    <Select
                        placeholder="操作结果"
                        value={filterOperateResult}
                        onChange={setFilterOperateResult}
                        style={{width: 120}}
                        options={OPERATE_RESULT_OPTIONS}
                        allowClear
                    />
                    <Input
                        placeholder="货号"
                        value={filterStyleId}
                        onChange={e => setFilterStyleId(e.target.value)}
                        style={{width: 130}}
                    />
                    <Button type="primary" icon={<SearchOutlined/>} onClick={handleSearch}>
                        搜索
                    </Button>
                    <Button icon={<ReloadOutlined/>} onClick={handleReset}>
                        重置
                    </Button>
                </Space>
                <Space>
                    <span>自动刷新</span>
                    <Switch checked={autoRefresh} onChange={setAutoRefresh}/>
                </Space>
            </div>
            <Table
                columns={columns}
                dataSource={taskItems}
                loading={loading}
                scroll={{x: 1130}}
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
