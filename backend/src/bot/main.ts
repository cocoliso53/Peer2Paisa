import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})
import { Telegraf, Markup } from "telegraf";
import { createHash, randomUUID } from 'crypto';
import { isAddress } from 'viem';
import { checkAmountFromBot, confirmFiatReceivedFromBot, createEscrowFromBot, markAsFundedFromBot, refundSellerFromBot } from '../utils/botSmartContractAdapter';
import { deltaWrapped } from '../utils/cljs-wrapper'
import { getOrderById, updateOrder, getActiveOrderByParticipant, getOrdersByParticipantAndStatus } from '../db';

const explorerBaseURL = "https://basescan.org/"
const helperCreateHash = (): string => {
    const uuid = randomUUID().toString()
    return createHash('md5').update(uuid, 'utf8').digest('base64url')
}

const s_0 = () => ({
    state: 's0',
    status: 'active',
    orderId: helperCreateHash(),

})

type DeleteDetails = {
    chat: string,
    id?: number
}

type KeyboardMessage = {
    text: string,
    callback: string
}

type OrderBookMessage = {
    text: string[],
    keyboard: KeyboardMessage
}

type SendMessageData = {
    chatId: number,
    text: string,
    parseMode?: string
}

type Effects = {
    reply?: string[],
    deleteMessage?: DeleteDetails[] 
    orderBookMessage?: OrderBookMessage,
    sendMessage?: SendMessageData[],
    answerQuery?: boolean,
}

type EffectsResponse = {
    reply?: number[]
    orderMessageId?: number
}

const runEffects = async (ctx: any, effects: Effects): Promise<EffectsResponse >=> {
    const res: EffectsResponse = {}

    if (effects.reply) {
        res.reply = []
        for (const i in effects.reply) {
            const m = await ctx.reply(effects.reply[i])
            res.reply.push(m.message_id)
        }
    }

    if (effects.deleteMessage) {
        for (const i in effects.deleteMessage) {
            if (effects.deleteMessage[i].chat === "ctx") {
                await ctx.telegram.deleteMessage()
            }

            const { id:lastMessageId } = effects.deleteMessage[i] 
            await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId)
        }
    }

    if (effects.orderBookMessage) {
        const { text, keyboard } = effects.orderBookMessage
        const { text:inlineText, callback} = keyboard

        const orderMessage = await ctx.telegram.sendMessage(
            channelId,
            text,
            {
                reply_markup: {
                    inline_keyboard: [
                        [
                            { text: inlineText, callback_data: callback}
                        ]
                    ]
                }
            }
        )

        res.orderMessageId = orderMessage.message_id
    }

    if (effects.answerQuery) {
        await ctx.answerCbQuery()
    }

    if (effects.sendMessage) {
        await Promise.all(
            effects.sendMessage.map(({ chatId, text, parseMode }) =>
                ctx.telegram.sendMessage(
                    chatId,
                    text,
                    parseMode ? { parse_mode: parseMode } : undefined
                )
            )
        );
    }

    return res
}

const channelId = Number(process.env.TELEGRAM_CHANNEL)
const bot = new Telegraf(process.env.TELEGRAM_BOT!);
const orderbook = process.env.ORDERBOOK_LINK


bot.start(async ctx => {
    if (!ctx.from.username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    await ctx.reply(`Welcome, use this bot to place and configure your orders. subscribe to ${orderbook} to see all active orders`);
});

bot.command('create', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Can't use the bot with no username");
        return;
    }

    try {
        const activeResponse = await getActiveOrderByParticipant(username);
        const hasActiveOrder = Boolean(activeResponse?.docs?.length);

        if (hasActiveOrder) {
            await ctx.reply("You can't place a new order while you have one active");
            return;
        }

        await ctx.reply(
            'Sell or buy?',
            Markup.inlineKeyboard([
                [Markup.button.callback('Sell', 'sell'), Markup.button.callback('Buy', 'buy')],
            ]),
        );
    } catch (error) {
        console.error('Unable to check active orders', error);
        await ctx.reply('Something went wrong while checking your orders. Please try again later.');
    }
});

bot.action(/^(sell|buy)$/, async ctx => {
    const action = ctx.match[1];
    const username = ctx.from.username;

    if (!username) {
        await ctx.answerCbQuery();
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    await ctx.answerCbQuery();
    await ctx.deleteMessage();

    const baseState = s_0();
    const { state: nextState, effects } = deltaWrapped(baseState, {
        event: action,
        data: { username },
    });

    const { reply } = await runEffects(ctx, effects);
    let stateToPersist = nextState;

    const lastMessageId = reply?.[reply.length - 1];

    if (lastMessageId) {
        const { state } = deltaWrapped(stateToPersist, {
            event: 'setLastMessageId',
            data: { messageId: lastMessageId },
        });
        stateToPersist = state;
    }

    await updateOrder(stateToPersist);
});

// NOTE:  Maybe I should use a webhook to replace this logic
bot.command('funded', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    try {
        const response = await getOrdersByParticipantAndStatus(username, "waitingFunding");
        const activeCouch = response?.docs?.[0];

        if (!activeCouch) {
            await ctx.reply("You have no orders waiting for funding.");
            return;
        }

        if (!activeCouch.escrowAddress) {
            await ctx.reply("Escrow address not available yet. Please wait for the counterpart to share it.");
            return;
        }

        const txHash = await markAsFundedFromBot(activeCouch.escrowAddress);

        if (txHash) {
            const { state, effects } = deltaWrapped(activeCouch, { event: "escrowFunded" });
            await updateOrder(state);
            await runEffects(ctx, effects);
        } else if (activeCouch?.seller?.chatId) {
            const amount = activeCouch.amount ?? "the agreed";
            await ctx.telegram.sendMessage(
                activeCouch.seller.chatId,
                `Please confirm you deposited ${amount} XOC in the escrow address ${activeCouch.escrowAddress}`,
            );
        } else {
            await ctx.reply("Unable to verify the escrow funding transaction. Please try again.");
        }
    } catch (error) {
        console.error('Error processing /funded command', error);
        await ctx.reply("Something went wrong while checking the escrow. Please try again later.");
    }
});

bot.command('release', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    try {
        const response = await getOrdersByParticipantAndStatus(username, "waitingRelease");
        const activeCouch = response?.docs?.[0];

        if (!activeCouch) {
            await ctx.reply("You have no orders waiting for release.");
            return;
        }

        if (!activeCouch.escrowAddress) {
            await ctx.reply("Escrow address not available yet. Please try again shortly.");
            return;
        }

        const txHash = await confirmFiatReceivedFromBot(activeCouch.escrowAddress);

        if (txHash) {
            const { state, effects } = deltaWrapped(activeCouch, {
                event: "fundsReleased",
                data: { explorerUrl: explorerBaseURL + `tx/${txHash}` },
            });
            await updateOrder(state);
            await runEffects(ctx, effects);
        } else {
            const sellerChatId = activeCouch?.seller?.chatId;
            const buyerChatId = activeCouch?.buyer?.chatId;

            if (sellerChatId) {
                await ctx.telegram.sendMessage(
                    sellerChatId,
                    "There was an error releasing the funds. Please try again or contact support",
                );
            }

            if (buyerChatId) {
                await ctx.telegram.sendMessage(
                    buyerChatId,
                    "There was an error releasing the funds. Wait for the seller to release again or contact support",
                );
            }
        }
    } catch (error) {
        console.error('Error processing /release command', error);
        await ctx.reply("Something went wrong while releasing the escrow. Please try again later.");
    }
});

/*
bot.command('release', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.seller?.username === username &&
        o.status === 'active' &&
        o.step === 'funded'
    )

    const buyerChatId = activeOrder?.buyer?.chatId!
    
    await ctx.telegram.sendMessage(
        buyerChatId, 
        "You want to receive XOC or USDT", 
        Markup.inlineKeyboard([
                [ Markup.button.callback('XOC', 'XOC'), Markup.button.callback('USDT', 'USDT') ]
            ]
        )
    )

})
*/

bot.command('help', async ctx => {
    await ctx.reply("Please contact @cuaucortes for assistance")
})

bot.command('cancel', async ctx => {
    await ctx.reply("Cancel flow is being migrated to the new engine. Please contact support if you need to cancel an order.");
});

// Expose helpers needed once cancel flow is re-enabled.
export const legacyCancelHelpers = {
    checkAmountFromBot,
    refundSellerFromBot,
};

/* Legacy cancel implementation retained for reference until the CLJS flow is ready.
bot.command('cancel', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        (o.buyer?.username === username ||
        o.seller?.username === username ) &&
        o.status === 'active'
    )
    const orderId = activeOrder?.orderId

    if(!activeOrder) {
        ctx.reply("You have no active orders")
    }


    if (activeOrder && (
        activeOrder.step === 'addressSet' ||
        activeOrder.step === 'amountSet' ||
        activeOrder.step === 'watingCounterpart')) {

            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                   ? o
                   : {
                    ...o,
                    step: 'canceled',
                    status: 'done'
                }
            )
            
            await ctx.reply("Order canceled succesfully")
            const orderMessageId = activeOrder.orderMessageId
            if (orderMessageId) {
                await ctx.telegram.deleteMessage(
                    channelId,
                    orderMessageId
                )
            }
        } else 
        
        if (activeOrder?.seller?.username === username) {
            switch (activeOrder.step) {

                case 'taken':
                case 'waitingFunding':
                    orders = orders.map((o): Order =>
                    o.orderId !== orderId 
                        ? o
                        : {
                            ...o,
                            step: 'canceled',
                            status: 'done'
                        }
                    )

                    await ctx.reply("Order canceled succesfully")
                    await ctx.telegram.sendMessage(activeOrder.buyer?.chatId!, "Seller canceled the order")
                    break
                    
                case 'funded':
                    await ctx.reply("Only buyer can initiate order cancelation right now")
                    break
                    
                default:
                    await ctx.reply("Can't cancel order at current stage")

            }
        } else 

        if (activeOrder?.buyer?.username === username) {
            switch (activeOrder.step) {

                case 'taken':
                case 'waitingFunding':
                case 'funded':
                    orders = orders.map((o): Order =>
                    o.orderId !== orderId 
                        ? o
                        : {
                            ...o,
                            step: 'canceled',
                            status: 'done'
                        }
                    )

                    await ctx.reply("Order canceled succesfully")
                    await ctx.telegram.sendMessage(activeOrder.buyer?.chatId!, "Buyer canceled the order")

                    const escrowAddress = activeOrder.escrowAddress
                    const amount = escrowAddress ? await checkAmountFromBot(escrowAddress) : null
                    if (amount && amount > 0) {
                        await markAsFundedFromBot(escrowAddress!)
                        // It would be better to wait for receipt once tx is confirmed
                        await new Promise(resolve => setTimeout(resolve, 5000));
                        const txHashRefund = await refundSellerFromBot(escrowAddress!)
                        const explorerUrl = explorerBaseURL + `/tx/${txHashRefund}`
                        const nextMessage = txHashRefund ? `Funds have been returned on tx ${explorerUrl}` : `Something went wrong, please contact support`
                        ctx.telegram.sendMessage(activeOrder.seller?.chatId!, nextMessage)
                        ctx.telegram.sendMessage(activeOrder.buyer.chatId!, "Order canceled")
                    }
                    
                    break
                    
                    
                default:
                    await ctx.reply("Can't cancel order at current stage")
                    
            }
        }
    }
});
*/

bot.on('text', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    const couchResponse = await getActiveOrderByParticipant(username);
    const activeCouch = couchResponse?.docs?.[0];

    if (!activeCouch) {
        await ctx.reply("You have no active orders.");
        return;
    }

    const text = ctx.message.text;
    const baseUser = {
        username,
        address: text,
        chatId: ctx.from.id,
    };

    const eventObject = (() => {
        switch (activeCouch.state) {
            case 'waitingNewOrderAmount':
                return { event: "setAmount", data: { text } };
            case 'waitingSetAddress':
                return { event: "setAddress", data: { text, user: baseUser } };
            case 'watingCounterpartAddress':
                if (!isAddress(text)) {
                    return null;
                }
                return {
                    event: "setCounterpartAddress",
                    data: { counterpart: baseUser },
                };
            case 'watingCounterpartSetAmount':
                return { event: "setAmountCounterpart", data: { text } };
            default:
                return null;
        }
    })();

    if (!eventObject) {
        if (activeCouch.state === 'watingCounterpartAddress') {
            await ctx.reply("Invalid address, please try again");
        }
        return;
    }

    const { state: newState, effects } = deltaWrapped(activeCouch, eventObject);
    const { reply, orderMessageId } = await runEffects(ctx, effects);

    let stateToPersist = newState;

    if (reply?.length || orderMessageId) {
        const eventName = orderMessageId ? "setMessagesIds" : "setLastMessageId";
        const payload: Record<string, unknown> = {};

        if (reply?.length) {
            payload.messageId = reply[0];
        }
        if (orderMessageId) {
            payload.orderMessageId = orderMessageId;
        }

        const { state } = deltaWrapped(stateToPersist, {
            event: eventName,
            data: payload,
        });
        stateToPersist = state;
    }

    await updateOrder(stateToPersist);

    if (stateToPersist.state === "waitingFunding" && effects.createEscrow) {
        const { buyerAddress, sellerAddress, amount } = effects.createEscrow;
        const escrowAddress = await createEscrowFromBot(sellerAddress, buyerAddress, amount);

        if (!escrowAddress) {
            console.error("Failed to create escrow for order:", stateToPersist.orderId);
            await ctx.reply("Sorry, we couldn't create the escrow contract. Please contact support.");
            return;
        }

        const escrowURL = explorerBaseURL + `/address/${escrowAddress}`;

        const { state: escrowState, effects: escrowEffects } = deltaWrapped(stateToPersist, {
            event: "setEscrowAddress",
            data: {
                escrow: escrowAddress,
                escrowUrl: escrowURL,
            },
        });

        await updateOrder(escrowState);
        await runEffects(ctx, escrowEffects);
    }
});


/*
bot.action(/^(XOC|USDT)/, async ctx => {
    const token = ctx.match[0]
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.buyer?.username === username &&
        o.status === 'active' &&
        o.step === 'funded'
    )

    const orderId = activeOrder?.orderId
    const escrow = activeOrder?.escrowAddress!
    const buyerChatId = activeOrder?.buyer?.chatId!
    const sellerChatId = activeOrder?.seller?.chatId!

    await ctx.answerCbQuery();

    await ctx.deleteMessage();

    const txHash = token === "XOC" ? await confirmFiatReceivedFromBot(escrow) : await swapAndSendFromBot(escrow)
    const explorerUrl = explorerBaseURL + `/tx/${txHash}`

    if (txHash) {
        await ctx.telegram.sendMessage(
            sellerChatId,
            "Transaction finished succesfully. Thanks for using our bot!"
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            `txHash: ${explorerUrl}`
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            "Transaction finished succesfully. Thanks for using our bot!"
        )

        orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step: 'done',
                        status: 'done'
                        }
            )
    } else {
        await ctx.telegram.sendMessage(
            sellerChatId,
            "There was an error releasing the funds. Please try again or contact support"
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            "There was an error releasing the funds. Wait for the seller to release again or contact support"
        )
    }
}) 
*/

bot.action(/^take:(.+)$/, async ctx => {
    const id = ctx.match[1];
    const orderCouch = await getOrderById(id);

    if (!orderCouch) {
        await ctx.answerCbQuery();
        await ctx.reply("Order not found. It might have been completed or canceled.");
        return;
    }

    const counterpart = {
        username: ctx.from.username!,
        chatId: ctx.from.id,
    };

    try {
        const amountRange = orderCouch.range
        const event = {
            event: amountRange ? "takeOrderRange" : "takeOrder",
            data: { 
                counterpart,
                takerChatId: ctx.from.id
            }
        }

        const { state, effects } = deltaWrapped(orderCouch, event);
        await runEffects(ctx, effects);
        await updateOrder(state);
    } catch (error) {
        console.error(`Error while taking order ${id}`, error);
        await ctx.reply("Something went wrong while taking the order. Please try again later.");
    }
});

bot.on('channel_post', ctx => {
  console.log('Channel ID:', ctx.channelPost.chat.id);
});


bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))
