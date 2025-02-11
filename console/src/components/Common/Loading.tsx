import {Spin} from "antd";
import React from "react";

// 定义 Loading 组件的属性类型
interface LoadingProps {
    loading: boolean;
    error?: string | null; // 允许 error 为 null
    content: React.ReactNode; // content 可以是任何 React 节点
}

export const Loading: React.FC<LoadingProps> = ({loading, error, content}) => {
    return (
        <div>
            {
                loading ? (
                    <div>
                        <Spin/>
                    </div>
                ) : error ? (
                    <div>
                        <p>Error: {error}</p>
                        <button onClick={() => window.location.reload()}>
                            Retry
                        </button>
                    </div>
                ) : (
                    <div>
                        {content}
                    </div>
                )
            }
        </div>
    )
}