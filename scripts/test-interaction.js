#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

let puppeteer;
try {
  puppeteer = require('puppeteer-core');
} catch (e) {
  console.error('puppeteer-core not found, please install: npm install puppeteer-core');
  process.exit(1);
}

const DEFAULT_EDGE_PATHS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

function findBrowserPath() {
  for (const browserPath of DEFAULT_EDGE_PATHS) {
    if (fs.existsSync(browserPath)) {
      return browserPath;
    }
  }
  return '';
}

function log(message) {
  const stamp = new Date().toLocaleString('zh-CN', { hour12: false });
  console.log(`[${stamp}] ${message}`);
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runInteractionTests() {
  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  
  if (!browserPath) {
    console.error('找不到 Edge 浏览器，请设置 PUPPETEER_EXECUTABLE_PATH 环境变量');
    process.exit(1);
  }

  log(`找到浏览器: ${browserPath}`);

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: false,
    ignoreHTTPSErrors: true,
    args: ['--ignore-certificate-errors', '--window-size=1920,1080'],
    defaultViewport: {
      width: 1920,
      height: 1080,
    },
  });

  const page = await browser.newPage();
  page.setDefaultTimeout(60000);
  
  // 监听网络请求
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/tasks')) {
      log(`  网络请求: ${request.method()} ${request.url()}`);
    }
  });
  
  // 监听网络响应
  page.on('response', (response) => {
    if (response.url().includes('/api/v1/tasks')) {
      log(`  响应状态: ${response.status()} ${response.url()}`);
    }
  });
  
  // 监听页面错误
  page.on('pageerror', (error) => {
    console.error('页面错误:', error);
  });

  try {
    log('=== 开始前端交互测试 ===');
    log('');
    
    // 记录测试开始时间
    testResults.startTime = new Date();

    // 1. 访问页面
    log('测试步骤 1/5: 访问 dialogue 页面');
    try {
      await page.goto('http://localhost:8080/dialogue/', {
        waitUntil: 'networkidle2',
        timeout: 30000
      });
      log('页面加载完成');
      recordTestStep('访问 dialogue 页面', 'passed', '页面成功加载');
      await sleep(2000);
    } catch (error) {
      log(`  ❌ 页面加载失败: ${error.message}`);
      recordTestStep('访问 dialogue 页面', 'failed', error.message);
      throw error;
    }

    // 2. 检查页面元素
    log('');
    log('测试步骤 2/5: 检查页面元素');
    const hasSidebar = await page.$('.sidebar') !== null;
    const hasWorkspace = await page.$('.workspace') !== null;
    const hasDetails = await page.$('.details') !== null;
    
    console.log('  侧边栏:', hasSidebar ? '✅ 存在' : '❌ 缺失');
    console.log('  工作区:', hasWorkspace ? '✅ 存在' : '❌ 缺失');
    console.log('  详情面板:', hasDetails ? '✅ 存在' : '❌ 缺失');
    
    const allElementsPresent = hasSidebar && hasWorkspace && hasDetails;
    recordTestStep('检查页面元素', allElementsPresent ? 'passed' : 'failed', 
      `侧边栏: ${hasSidebar}, 工作区: ${hasWorkspace}, 详情面板: ${hasDetails}`);

    // 3. 创建新会话并点击
    log('');
    log('测试步骤 3/5: 创建新会话');
    let sessionCreated = false;
    
    // 找到新会话输入框
    const sessionTitleInput = await page.$('#sessionTitle');
    if (sessionTitleInput) {
      log('  找到新会话输入框');
      await sessionTitleInput.click();
      await sleep(500);
      
      // 输入会话标题
      const sessionTitle = 'Puppeteer 测试会话';
      await page.type('#sessionTitle', sessionTitle, { delay: 30 });
      log(`  输入会话标题: "${sessionTitle}"`);
      await sleep(500);
      
      // 点击新建按钮
      const createSessionButton = await page.$('.sidebar__form button[type="submit"]');
      if (createSessionButton) {
        log('  点击新建会话按钮');
        await createSessionButton.click();
        await sleep(2000);
        
        // 检查是否创建成功
        const sessionCards = await page.$$('.session-card');
        console.log('  会话卡片数量:', sessionCards.length);
        
        const activeCard = await page.$('.session-card.is-active');
        console.log('  活跃会话:', activeCard ? '✅ 已激活' : '❌ 未激活');
        
        sessionCreated = !!activeCard;
      } else {
        log('  ⚠️ 新建会话按钮未找到');
      }
    } else {
      log('  ⚠️ 新会话输入框未找到');
      // 尝试点击现有会话卡片
      const sessionCards = await page.$$('.session-card');
      if (sessionCards.length > 0) {
        log(`  找到 ${sessionCards.length} 个会话卡片，点击第一个`);
        await sessionCards[0].click();
        await sleep(1000);
        
        const activeCard = await page.$('.session-card.is-active');
        console.log('  活跃会话:', activeCard ? '✅ 已激活' : '❌ 未激活');
        sessionCreated = !!activeCard;
      }
    }
    
    recordTestStep('创建新会话', sessionCreated ? 'passed' : 'failed', 
      sessionCreated ? '会话创建成功并激活' : '会话创建失败');


    // 4. 测试任务创建
    log('');
    log('测试步骤 4/5: 测试任务创建');
    let taskCreated = false;
    
    // 检查会话状态
    const sessionStatus = await page.evaluate(() => {
      const sessionLabel = document.querySelector('.composer__session-label');
      return sessionLabel ? sessionLabel.textContent : '未知';
    });
    log(`  当前会话状态: ${sessionStatus}`);
    
    // 找到输入框 (#taskIntent)
    const inputArea = await page.$('#taskIntent');
    if (inputArea) {
      log('  找到输入框，准备输入测试任务');
      await inputArea.click();
      await sleep(500);
      
      // 输入测试任务
      const testTask = '这是一个 Puppeteer 自动化测试任务';
      await page.type('#taskIntent', testTask, { delay: 50 });
      log(`  输入任务内容: "${testTask}"`);
      await sleep(500);
      
      // 检查输入内容
      const inputValue = await page.$eval('#taskIntent', el => el.value);
      console.log('  输入内容验证:', inputValue === testTask ? '✅ 正确' : '❌ 错误');
      
      // 切换到任务模式（需要先展开 details）
      const composerRouting = await page.$('#composerRouting');
      if (composerRouting) {
        log('  展开 composer-routing');
        await composerRouting.click();
        await sleep(500);
        
        const taskModeButton = await page.$('button[data-composer-mode="task"]');
        if (taskModeButton) {
          log('  切换到任务模式');
          await taskModeButton.click();
          await sleep(500);
          
          const activeMode = await page.evaluate(() => {
            const activeBtn = document.querySelector('.composer-mode-switch .filter-chip.is-active');
            return activeBtn ? activeBtn.dataset.composerMode : '未知';
          });
          console.log('  当前模式:', activeMode);
        } else {
          log('  ⚠️ 任务模式按钮未找到');
        }
      } else {
        log('  ⚠️ composer-routing 未找到');
      }
      
      // 检查发送按钮状态
      const sendButton = await page.$('#submitTaskButton');
      if (sendButton) {
        const isDisabled = await page.$eval('#submitTaskButton', el => el.disabled);
        console.log('  发送按钮状态:', isDisabled ? '❌ 已禁用' : '✅ 可用');
        
        if (!isDisabled) {
          log('  点击发送按钮');
          await sendButton.click();
          await sleep(5000);
          
          // 检查是否创建成功
          const taskItems = await page.$$('.dialogue-task');
          console.log('  创建后任务数量:', taskItems.length);
          
          taskCreated = taskItems.length > 0;
          
          // 额外检查消息列表
          const messageItems = await page.$$('.message-item');
          console.log('  消息列表数量:', messageItems.length);
          
          // 检查消息面板内容
          const messagePanelContent = await page.evaluate(() => {
            const panel = document.querySelector('.message-panel');
            return panel ? panel.innerHTML.slice(0, 500) : '空';
          });
          console.log('  消息面板内容:', messagePanelContent);
          
          // 检查是否有任何 .thread-item
          const threadItems = await page.$$('.thread-item');
          console.log('  线程项数量:', threadItems.length);
          
          // 检查页面状态
          const threadDrawer = await page.$('.thread-drawer');
          if (threadDrawer) {
            const drawerContent = await page.evaluate(() => {
              const drawer = document.querySelector('.thread-drawer');
              return drawer ? drawer.innerHTML.slice(0, 200) : '';
            });
            console.log('  任务面板内容预览:', drawerContent || '空');
          }
        } else {
          log('  ⚠️ 发送按钮已禁用，无法提交');
          // 尝试直接提交表单
          log('  尝试直接调用表单 submit');
          await page.evaluate(() => {
            const form = document.getElementById('taskForm');
            if (form) {
              form.requestSubmit();
            }
          });
          await sleep(5000);
          
          const taskItems = await page.$$('.dialogue-task');
          console.log('  表单提交后任务数量:', taskItems.length);
          taskCreated = taskItems.length > 0;
        }
      } else {
        log('  ⚠️ 发送按钮未找到');
      }
    } else {
      log('  ⚠️ 输入框未找到');
    }
    
    recordTestStep('测试任务创建', taskCreated ? 'passed' : 'failed', 
      taskCreated ? '任务创建成功' : '任务创建失败');


    // 5. 测试任务卡片点击（模态框）
    log('');
    log('测试步骤 5/5: 测试任务卡片点击（模态框）');
    let modalTestPassed = false;
    const taskCards = await page.$$('.dialogue-task');
    if (taskCards.length > 0) {
      log(`  找到 ${taskCards.length} 个任务卡片`);
      await taskCards[0].click();
      log('  点击第一个任务卡片');
      await sleep(1500);
      
      // 检查模态框是否出现
      const modal = await page.$('.modal-container');
      console.log('  模态框显示:', modal ? '✅ 已显示' : '❌ 未显示');
      
      // 关闭模态框（使用 evaluate 避免元素遮挡问题）
      if (modal) {
        log('  关闭模态框');
        await page.evaluate(() => {
          // 尝试点击关闭按钮
          const closeBtn = document.getElementById('modalCloseBtn');
          if (closeBtn) {
            closeBtn.click();
            return;
          }
          // 尝试另一个关闭按钮
          const closeButton = document.getElementById('modalCloseButton');
          if (closeButton) {
            closeButton.click();
            return;
          }
          // 如果没有关闭按钮，点击模态框背景关闭
          const modalElement = document.getElementById('taskDetailModal');
          if (modalElement) {
            modalElement.click();
          }
        });
        await sleep(500);
        
        // 检查模态框是否关闭
        const modalDisplay = await page.evaluate(() => {
          const modal = document.getElementById('taskDetailModal');
          return modal ? modal.style.display : 'none';
        });
        console.log('  模态框关闭:', modalDisplay === 'none' ? '✅ 已关闭' : '❌ 未关闭');
        
        modalTestPassed = modalDisplay === 'none';
      }
    } else {
      log('  ⚠️ 没有任务卡片，跳过模态框测试');
    }
    
    recordTestStep('测试任务卡片点击（模态框）', 
      modalTestPassed ? 'passed' : (taskCards.length > 0 ? 'failed' : 'warning'), 
      modalTestPassed ? '模态框显示和关闭成功' : (taskCards.length > 0 ? '模态框关闭失败' : '无任务卡片可测试'));

    // 记录测试结束时间
    testResults.endTime = new Date();

    // 截图保存
    const screenshotPath = path.join(__dirname, '../interaction_test.png');
    log('');
    log(`保存测试截图到: ${screenshotPath}`);
    await page.screenshot({
      path: screenshotPath,
      fullPage: true,
      type: 'png'
    });

    // 生成测试报告
    const report = await generateTestReport();
    const reportPath = path.join(__dirname, '../test_report.md');
    await fs.promises.writeFile(reportPath, report, 'utf-8');
    log(`测试报告已保存到: ${reportPath}`);

    log('');
    log('=== 前端交互测试完成 ===');

  } catch (error) {
    console.error('测试过程出错:', error);
    // 保存错误截图
    const errorPath = path.join(__dirname, '../error_screenshot.png');
    await page.screenshot({ path: errorPath, fullPage: true });
    console.log(`错误截图已保存到: ${errorPath}`);
    process.exit(1);
  } finally {
    log('');
    log('关闭浏览器...');
    await browser.close();
  }
}

// 测试结果数据
let testResults = {
  startTime: null,
  endTime: null,
  steps: [],
  networkRequests: [],
  errors: []
};

// 添加网络请求记录
function recordNetworkRequest(method, url, status = null) {
  testResults.networkRequests.push({
    method,
    url,
    status,
    timestamp: new Date().toISOString()
  });
}

// 添加测试步骤结果
function recordTestStep(stepName, status, details = '') {
  testResults.steps.push({
    stepName,
    status,
    details,
    timestamp: new Date().toISOString()
  });
}

// 生成测试报告
async function generateTestReport() {
  const duration = testResults.endTime - testResults.startTime;
  const durationStr = `${Math.floor(duration / 1000)}秒${(duration % 1000)}毫秒`;
  
  const passedCount = testResults.steps.filter(s => s.status === 'passed').length;
  const failedCount = testResults.steps.filter(s => s.status === 'failed').length;
  const warningCount = testResults.steps.filter(s => s.status === 'warning').length;
  
  const report = `# Agent Cloud Harness 前端交互测试报告

## 测试概览

| 项目 | 值 |
|------|-----|
| 测试时间 | ${testResults.startTime.toLocaleString('zh-CN')} |
| 测试时长 | ${durationStr} |
| 通过步骤 | ${passedCount} |
| 失败步骤 | ${failedCount} |
| 警告步骤 | ${warningCount} |

## 测试步骤详情

${testResults.steps.map((step, index) => `### ${index + 1}. ${step.stepName}

- **状态**: ${step.status === 'passed' ? '✅ 通过' : step.status === 'failed' ? '❌ 失败' : '⚠️ 警告'}
- **时间**: ${new Date(step.timestamp).toLocaleTimeString('zh-CN')}
${step.details ? `- **详情**: ${step.details}` : ''}

`).join('')}

## 网络请求记录

| 序号 | 方法 | URL | 状态码 |
|------|------|-----|--------|
${testResults.networkRequests.map((req, index) => `| ${index + 1} | ${req.method} | ${req.url} | ${req.status || '-'} |`).join('\n')}

## 测试环境

- **浏览器**: Microsoft Edge
- **视口尺寸**: 1920x1080
- **测试目标**: http://localhost:8080/dialogue/

---

*报告生成时间: ${new Date().toLocaleString('zh-CN')}*
`;
  
  return report;
}

runInteractionTests();
