import dotenv from 'dotenv'
import path from 'path'

dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})

import { Telegraf, Markup } from "telegraf";
import { message } from "telegraf/filters";

// Temp storage
const users: string[] = []
const bot = new Telegraf(process.env.TELEGRAM_BOT!);

bot.start((ctx) => {
    if (ctx.from.username) {
        users.push(ctx.from.username)
        ctx.reply("Welcome, use this bot to place and configure your orders. subscribe to https://t.me/+9W7SKA0R-Z42YmFh to see all active orders")
    } else {
        ctx.reply("Please set a username to use the bot")
    }
})

bot.command('create', ctx => {
    const username  = ctx.from.username
    if (!username) {
        ctx.reply("Can't use the bot with no username")
    }
    const activeUser = users.includes(username!)
    if (activeUser && username) {
        users.push(username)
        ctx.reply(
            'Sell or buy?',
            Markup.inlineKeyboard([
                [ Markup.button.callback('Sell', 'sell'), Markup.button.callback('Buy', 'buy') ]
            ])
        )
    }
    // ctx.telegram.sendMessage(-1002641616927,"Se creó una orden")
})

bot.action('sell', ctx => {
    ctx.answerCbQuery()
    ctx.deleteMessage()
    ctx.reply('Enter amount to sell')
})

bot.action('buy', ctx => {
    ctx.answerCbQuery()
    ctx.deleteMessage()
    ctx.reply('Entner amount to buy')
})

bot.on('message', (ctx) => {
    console.log("de", ctx.from)
    ctx.reply(
    `I see you!\n\n` +
    `ID: ${ctx.from.id}\n` +
    `Name: ${ctx.from.first_name}${ctx.from.last_name ? ' ' + ctx.from.last_name : ''}\n` +
    `Username: @${ctx.from.username || 'none'}`
  );

})

bot.on('channel_post', ctx => {
  console.log('Channel ID:', ctx.channelPost.chat.id);
});


bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))