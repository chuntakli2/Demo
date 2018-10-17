//
//  ViewController.swift
//  Demo
//
//  Created by Chun Tak Li on 5/2/2018.
//  Copyright © 2018 Chun Tak Li. All rights reserved.
//

import UIKit

class ViewController: UIViewController {
    
    fileprivate var webView = UIWebView()

    fileprivate var hasLoadedConstraints = false
    
    // MARK: - Initialisation
    
    convenience init() {
        self.init(nibName: nil, bundle: nil)
    }
    
    override init(nibName nibNameOrNil: String?, bundle nibBundleOrNil: Bundle?) {
        super.init(nibName: nibNameOrNil, bundle: nibBundleOrNil)
        
        self.setup()
    }
    
    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        
        self.setup()
    }
    
    deinit {
        
    }
    
    // MARK: - Accessors
    
    // MARK: - Public Methods
    
    func setup() {
        
    }

    // MARK: - Subviews

    fileprivate func setupWebView() {
        let request = URLRequest(url: URL(fileURLWithPath: Bundle.main.path(forResource: "index", ofType: "html", inDirectory: "EmbeddedJS")!))
        self.webView.loadRequest(request)
    }
    
    func setupSubviews() {
        self.setupWebView()
        self.webView.translatesAutoresizingMaskIntoConstraints = false
        self.view.addSubview(self.webView)
    }
    
    override func updateViewConstraints() {
        if (!self.hasLoadedConstraints) {
            let views = ["web": self.webView]
            
            self.view.addConstraints(NSLayoutConstraint.constraints(withVisualFormat: "|[web]|", options: .directionMask, metrics: nil, views: views))
            
            self.view.addConstraints(NSLayoutConstraint.constraints(withVisualFormat: "V:|[web]|", options: .directionMask, metrics: nil, views: views))
            
            self.hasLoadedConstraints = true
        }
        super.updateViewConstraints()
    }
    
    // MARK: - View lifecycle
    
    override func loadView() {
        self.view = UIView()
        self.view.backgroundColor = .white
//        self.view.tintColor = TINT_COLOUR
        self.view.translatesAutoresizingMaskIntoConstraints = true
        
        self.setupSubviews()
        self.updateViewConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // Do any additional setup after loading the view, typically from a nib.
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // Dispose of any resources that can be recreated.
    }


}

