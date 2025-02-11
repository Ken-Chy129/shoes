type App = {
    id: string;
    gmtCreate: string;
    gmtModified: string;
    name: string;
    description?: string;
    status: string;
};

type Namespace = {
    id: string;
    gmtCreate: string;
    gmtModified: string;
    appId: string;
    name: string;
    description?: string;
    className: string;
}

type Field = {
    id: string;
    gmtCreate: string;
    gmtModified: string;
    appId: string;
    namespaceId: string;
    namespace: string;
    className: string;
    name: string;
    description?: string;
};

type FieldValue = {
    namespace: string;
    className: string;
    description?: string;
    name: string;
    machineValueMap: {[key: string]: string};
};

type MachineFieldValue = {
    ipAddress: string;
    port: string;
    value: string;
}

type Template = {
    id: string;
    name: string;
    description?: string;
    key: string;
    value: string;
    label: string;
}