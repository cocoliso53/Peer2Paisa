import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})
import { Telegraf, Markup } from "telegraf";
import { createHash, randomUUID } from 'crypto';

type Order = {
    buyer?: {
        username: string,
        address?: string,
        chatId?: number
    },
    seller?: {
        username: string,
        address?: string,
        chatId?: number,
    }, 
    orderId: string,
    step: 'created' | 'amountSet' | 'addressSet' | 'watingCounterpart' | 'taken' | 'funded' | 'released' | 'dispute' | 'done' // step in progress
    status: 'active' | 'done'
    type: string
    amount?: string
    lastMessageId?: number
}

const helperCreateHash = (): string => {
    const uuid = randomUUID().toString()
    return createHash('md5').update(uuid, 'utf8').digest('base64url')
}

// Temp storage
let users: string[] = []
let orders: Order[] = []
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
    orderId: helperCreateHash(),
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
        const m2 = await ctx.reply(`Order created ${orderId}`,)

        
        ctx.telegram.sendMessage(
            -1002641616927,
            orderText,
        {
            reply_markup: {
                inline_keyboard: [
                    [
                        { text: "Take Order", callback_data: `take:${orderId}`}
                    ]
                ]
            }
        })

        const user = {
            username: ctx.from.username!, 
            address:  ctx.message.text,
            chatId: ctx.from.id
        }

        orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    step:"watingCounterpart",
                    lastMessageId: m2.message_id,
                    ...(orderType === "sell"
                        ? { seller: user }
                        : { buyer:  user }),
                }
        )
    } else if (activeOrder!.step === 'taken') {
        console.log("texto (address)?", ctx.message.text)
        await ctx.reply("Ok")
    }



})

bot.action(/^take:(.+)$/, async ctx => {
    const id = ctx.match[1];
    console.log("el id", id)

    const order = orders.find(o => o.orderId === id)
    const orderId = order?.orderId
    const orderType = order?.type

    const orderMakerChatId = orderType === "sell" ? order?.seller?.chatId! : order?.buyer?.chatId!
    const takerMessage = orderType === "sell" ? "Place enter the wallet where you want to receive MXNB" : "Enter address for refund (if necessary)"

    const counterpart = {
        username: ctx.from.username!,
        chatId: ctx.from.id
    }


    orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    step: "taken",
                    ...(orderType === "sell"
                        ? { buyer: counterpart }
                        : { seller:  counterpart }),
                }
    )

    await ctx.answerCbQuery();

    await ctx.deleteMessage();

    await ctx.telegram.sendMessage(
        orderMakerChatId,
        `Order #${id} has been taken. Waiting for the counter part to set their address`
    )


    await ctx.telegram.sendMessage(
        ctx.from.id,
        takerMessage
    );
})



bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))