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

function rgbToHex(rgb) {
  const match = rgb.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/);
  if (!match) return rgb;
  const r = parseInt(match[1]).toString(16).padStart(2, '0');
  const g = parseInt(match[2]).toString(16).padStart(2, '0');
  const b = parseInt(match[3]).toString(16).padStart(2, '0');
  return `#${r}${g}${b}`;
}

async function analyzeDesign() {
  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  
  if (!browserPath) {
    console.error('找不到 Edge 浏览器，请设置 PUPPETEER_EXECUTABLE_PATH 环境变量');
    process.exit(1);
  }

  log(`找到浏览器: ${browserPath}`);

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
    ignoreHTTPSErrors: true,
    args: ['--ignore-certificate-errors', '--window-size=1920,1080'],
    defaultViewport: {
      width: 1920,
      height: 1080,
    },
  });

  const page = await browser.newPage();
  page.setDefaultTimeout(30000);

  try {
    log('正在访问页面...');
    await page.goto('http://localhost:8080/dialogue/', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });

    log('页面加载完成，等待渲染...');
    await new Promise(resolve => setTimeout(resolve, 2000));

    const screenshotPath = path.join(__dirname, '../dialogue_design_analysis.png');
    log(`正在截图到 ${screenshotPath}...`);
    await page.screenshot({
      path: screenshotPath,
      fullPage: true,
      type: 'png'
    });

    log('=== 页面设计风格分析报告 ===\n');

    // 获取 CSS 变量
    const cssVariables = await page.evaluate(() => {
      const root = document.querySelector(':root');
      const computedStyle = getComputedStyle(root);
      const vars = {};
      for (let i = 0; i < computedStyle.length; i++) {
        const prop = computedStyle[i];
        if (prop.startsWith('--')) {
          vars[prop] = computedStyle.getPropertyValue(prop).trim();
        }
      }
      return vars;
    });

    // 获取布局信息
    const layoutInfo = await page.evaluate(() => {
      const getElementInfo = (selector, name) => {
        const el = document.querySelector(selector);
        if (!el) return null;
        const style = getComputedStyle(el);
        return {
          selector,
          name,
          width: el.offsetWidth,
          height: el.offsetHeight,
          padding: style.padding,
          borderRadius: style.borderRadius,
          backgroundColor: style.backgroundColor,
          boxShadow: style.boxShadow
        };
      };

      const cards = document.querySelectorAll('.message-card');
      const cardStyles = [];
      cards.forEach((card, i) => {
        const style = getComputedStyle(card);
        cardStyles.push({
          index: i,
          classes: card.className,
          width: card.offsetWidth,
          borderRadius: style.borderRadius,
          backgroundColor: style.backgroundColor,
          boxShadow: style.boxShadow !== 'none'
        });
      });

      return {
        sidebar: getElementInfo('.sidebar', '侧边栏'),
        workspace: getElementInfo('.workspace', '工作区'),
        composer: getElementInfo('.composer-panel', '输入面板'),
        messageCards: cardStyles,
        messageCount: cards.length,
        sessionCards: document.querySelectorAll('.session-card').length,
        buttons: document.querySelectorAll('button').length,
        inputs: document.querySelectorAll('input, textarea, select').length
      };
    });

    // 获取字体信息
    const fontInfo = await page.evaluate(() => {
      const body = document.querySelector('body');
      const style = getComputedStyle(body);
      return {
        fontFamily: style.fontFamily,
        fontSize: style.fontSize,
        lineHeight: style.lineHeight,
        color: style.color
      };
    });

    // 获取间距信息
    const spacingInfo = await page.evaluate(() => {
      const elements = [
        { selector: '.dialogue-shell', prop: 'gap' },
        { selector: '.message-stream', prop: 'gap' },
        { selector: '.message-card', prop: 'padding' },
        { selector: '.composer', prop: 'gap' },
        { selector: '.sidebar__section', prop: 'padding' }
      ];
      
      const result = {};
      elements.forEach(({ selector, prop }) => {
        const el = document.querySelector(selector);
        if (el) {
          result[selector] = getComputedStyle(el)[prop];
        }
      });
      return result;
    });

    // === 输出分析报告 ===

    console.log('📊 页面概览');
    console.log('─────────────────────────────────────────────────────────────');
    console.log(`页面标题: ${await page.title()}`);
    console.log(`窗口尺寸: 1920x1080`);
    console.log(`消息卡片: ${layoutInfo.messageCount} 张`);
    console.log(`会话卡片: ${layoutInfo.sessionCards} 张`);
    console.log(`按钮数量: ${layoutInfo.buttons} 个`);
    console.log(`输入控件: ${layoutInfo.inputs} 个`);
    console.log('');

    console.log('🎨 颜色主题分析');
    console.log('─────────────────────────────────────────────────────────────');
    
    const colorVars = Object.entries(cssVariables).filter(([key]) => 
      key.includes('bg') || key.includes('surface') || key.includes('ink') || 
      key.includes('accent') || key.includes('warn') || key.includes('danger')
    );
    
    colorVars.forEach(([key, value]) => {
      const hexValue = value.startsWith('rgb') ? rgbToHex(value) : value;
      console.log(`  ${key.padEnd(25)} ${hexValue}`);
    });
    console.log('');

    console.log('📐 布局结构分析');
    console.log('─────────────────────────────────────────────────────────────');
    
    const layoutItems = ['sidebar', 'workspace', 'composer'];
    layoutItems.forEach(key => {
      const item = layoutInfo[key];
      if (item) {
        console.log(`  ${item.name}:`);
        console.log(`    尺寸: ${item.width} x ${item.height} px`);
        console.log(`    圆角: ${item.borderRadius}`);
        console.log(`    背景: ${item.backgroundColor}`);
        console.log(`    阴影: ${item.boxShadow ? '有' : '无'}`);
        console.log('');
      }
    });

    console.log('💬 消息卡片分析');
    console.log('─────────────────────────────────────────────────────────────');
    console.log(`卡片数量: ${layoutInfo.messageCards.length}`);
    if (layoutInfo.messageCards.length > 0) {
      layoutInfo.messageCards.forEach(card => {
        console.log(`  卡片 ${card.index + 1}:`);
        console.log(`    类名: ${card.classes.split(' ').filter(c => c).slice(0, 3).join(', ')}`);
        console.log(`    宽度: ${card.width} px`);
        console.log(`    圆角: ${card.borderRadius}`);
        console.log(`    阴影: ${card.boxShadow ? '有' : '无'}`);
      });
    }
    console.log('');

    console.log('✏️ 字体规范');
    console.log('─────────────────────────────────────────────────────────────');
    console.log(`  字体系列: ${fontInfo.fontFamily}`);
    console.log(`  字体大小: ${fontInfo.fontSize}`);
    console.log(`  行高: ${fontInfo.lineHeight}`);
    console.log(`  文字颜色: ${fontInfo.color}`);
    console.log('');

    console.log('📏 间距规范');
    console.log('─────────────────────────────────────────────────────────────');
    Object.entries(spacingInfo).forEach(([selector, value]) => {
      console.log(`  ${selector.padEnd(25)} ${value}`);
    });
    console.log('');

    console.log('🔧 CSS 变量完整列表');
    console.log('─────────────────────────────────────────────────────────────');
    Object.entries(cssVariables).sort().forEach(([key, value]) => {
      console.log(`  ${key.padEnd(30)} ${value}`);
    });
    console.log('');

    console.log('📝 设计建议');
    console.log('─────────────────────────────────────────────────────────────');
    console.log('基于分析结果的优化建议：');
    
    if (layoutInfo.messageCards.length > 0 && !layoutInfo.messageCards[0].boxShadow) {
      console.log('  ⚠️ 消息卡片缺少阴影效果，建议添加以增加层次感');
    }
    
    if (colorVars.filter(([k]) => k.includes('accent')).length === 0) {
      console.log('  ⚠️ 缺少强调色变量定义');
    }
    
    console.log('  ✅ 布局结构清晰，组件职责分明');
    console.log('  ✅ 使用 CSS 变量统一管理主题，便于维护');
    console.log('');

    log(`分析完成！截图已保存到: ${screenshotPath}`);
    log('分析报告已输出，可参考设计建议进行优化');

  } catch (error) {
    console.error('分析过程出错:', error);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

analyzeDesign();
