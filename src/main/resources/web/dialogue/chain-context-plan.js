export function buildChainContextPlan(tasks, selectedTaskId) {
    const chain = Array.isArray(tasks) ? tasks.filter(Boolean) : [];
    if (chain.length === 0) {
        return {
            currentIndex: -1,
            currentTask: null,
            previousTask: null,
            nextTask: null,
            visibleTasks: [],
            hiddenTasks: [],
            hasDrawer: false,
            drawerSummary: ""
        };
    }
    const currentIndex = Math.max(0, chain.findIndex((task) => task?.id === selectedTaskId));
    const currentTask = chain[currentIndex] || chain[0] || null;
    const previousTask = currentIndex > 0 ? chain[currentIndex - 1] : null;
    const nextTask = currentIndex >= 0 && currentIndex < chain.length - 1 ? chain[currentIndex + 1] : null;
    const visibleTasks = [currentTask].filter(Boolean);
    const hiddenTasks = chain.filter((task) => task?.id !== currentTask?.id);
    return {
        currentIndex,
        currentTask,
        previousTask,
        nextTask,
        visibleTasks,
        hiddenTasks,
        hasDrawer: hiddenTasks.length > 0,
        drawerSummary: hiddenTasks.length > 0 ? `展开完整迭代链 · 还有 ${hiddenTasks.length} 个任务` : ""
    };
}
