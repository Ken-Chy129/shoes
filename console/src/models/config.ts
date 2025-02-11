type Category = {
    id: string;
    key: string;
    label: string;
    children: Category[];
    linkUrl?: string;
    icon?: string;
    disabled: boolean;
};

type Machine = {
    id: string;
    ipAddress: string;
    port: string;
};

type MenuDataItem = {
    authority?: string[] | string;
    children?: MenuDataItem[];
    hideChildrenInMenu?: boolean;
    hideInMenu?: boolean;
    icon?: string;
    locale?: string;
    name?: string;
    path: string;

    [key: string]: any;
}

