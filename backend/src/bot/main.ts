import dotenv from 'dotenv'
import path from 'path'
import { text } from 'stream/consumers';

dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})

import { Telegraf, Markup, session } from "telegraf";

type Order = {
    buyer?: {
        username: string,
        address?: string,
    },
    seller?: {
        username: string,
        address?: string
    }, 
    orderId: string,
    step: 'created' | 'amountSet' | 'addressSet' | 'watingCounterpart' | 'taken' | 'funded' | 'released' | 'dispute' | 'done' // step in progress
    status: 'active' | 'done'
    type: string
    amount?: string
    lastMessageId?: number
}

// Temp storage
let users: string[] = []
let orders: Order[] = []
const bot = new Telegraf(process.env.TELEGRAM_BOT!);

bot.use(session())

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
    const activeOrder = orders.some(o =>
        o.buyer?.username === username &&
        o.seller?.username === username &&
        o.status === 'active'
    )

    if (activeOrder) {
        ctx.reply("You can't place a new order while you have one active")
    }

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

bot.action(/^(sell|buy)$/, async ctx => {
  const action = ctx.match[1];

  await ctx.answerCbQuery();
  await ctx.deleteMessage();

  const m = await ctx.reply(`Enter amount to ${action} in MXN`);

  let order:Order = {
    orderId: crypto.randomUUID().toString(),
    step: 'amountSet',
    status: 'active',
    lastMessageId: m.message_id,
    type: action
  }

  if (action === 'sell') {
    order = {
        ...order,
        seller: {
            username: ctx.from.username!,
        }
    }
  } else if (action === 'buy') {
    order = {
        ...order,
        buyer: {
            username: ctx.from.username!
        }
    }
  }

  console.log("order", order)
  orders.push(order)
});

bot.on('text', async ctx => {
    
    const username = ctx.from.username!
    console.log("username acá", username)
    const activeOrder = orders.find(o =>
        (o.buyer?.username === username ||
        o.seller?.username === username ) &&
        o.status === 'active'
    )

    const orderId = activeOrder?.orderId
    const lastMessageId = activeOrder?.lastMessageId
    const orderType = activeOrder?.type

    console.log("Active order", activeOrder)

    if (!activeOrder) {
        await ctx.reply("Something went wrong")
    }

    if (activeOrder!.step === 'amountSet') {
        
        const nextMessage = orderType === 'sell' ? "Enter address for refund (if necessary)" : "Enter address to receive MXNB"
        await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
        const m2 = await ctx.reply(nextMessage)
        
        const updateOrders = orders.map(o => 
            o.orderId === orderId ? {
                ...o,
                amount: ctx.message.text,
                step: "addressSet" as Order["step"],
                lastMessageId: m2.message_id
            } : o
        )

        orders = updateOrders
    } else if (activeOrder!.step === 'addressSet') {
        const amount = activeOrder?.amount
        const orderText = orderType === 'sell' ? `Selling ${amount} MXNB` : `Buying ${amount} MXNB`
        await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
        const m2 = await ctx.reply(`Order created ${orderId}`)

        
        ctx.telegram.sendMessage(-1002641616927,orderText)

        if (orderType === 'sell') {
            const updateOrders = orders.map(o => 
                o.orderId === orderId ? {
                    ...o,
                    step: "watingCounterpart" as Order["step"],
                    lastMessageId: m2.message_id,
                    seller: {
                        username: ctx.from.username!,
                        address: ctx.message.text
                    }
                } : o
            )

            orders = updateOrders
        } else {
            const updateOrders = orders.map(o => 
                o.orderId === orderId ? {
                    ...o,
                    step: "watingCounterpart" as Order["step"],
                    lastMessageId: m2.message_id,
                    buyer: {
                        username: ctx.from.username!,
                        address: ctx.message.text
                    }
                } : o
            )

            orders = updateOrders
        }
    }



})

bot.on('channel_post', ctx => {
  console.log('Channel ID:', ctx.channelPost.chat.id);
});


bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))