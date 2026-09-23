
package chatty.gui.components.menus;

import chatty.gui.DockedDialogHelper;
import chatty.gui.components.AutoModDialog;
import java.awt.event.ActionEvent;
import javax.swing.JMenuItem;

/**
 *
 * @author tduva
 */
public class AutoModContextMenu extends ContextMenu {

    private final AutoModDialog.Item item;
    private final AutoModContextMenuListener listener;
    
    public AutoModContextMenu(AutoModDialog.Item item, DockedDialogHelper dockedHelper, AutoModContextMenuListener listener) {
        if (item != null) {
            JMenuItem approve = addItem("approve", "Approve [A]");
            approve.setMnemonic('A');
            approve.setEnabled(item.canRequestAction());
            JMenuItem deny = addItem("reject", "Deny [D]");
            deny.setMnemonic('D');
            deny.setEnabled(item.canRequestAction());
            addSeparator();
            addItem("copy", "Copy Message");
            addItem("user", "User Info");
            addSeparator();
        }
        addItem("help", "Help");
        addSeparator();
        addItem("close", "Close [Q]").setMnemonic('Q');
        addSeparator();
        dockedHelper.addToContextMenu(this);
        
        this.item = item;
        this.listener = listener;
    }
    
    @Override
    public void actionPerformed(ActionEvent e) {
        this.listener.itemClicked(item, e);
    }

    public static interface AutoModContextMenuListener {

        public void itemClicked(AutoModDialog.Item item, ActionEvent e);
    }
}
